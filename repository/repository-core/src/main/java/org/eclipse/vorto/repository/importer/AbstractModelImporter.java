/**
 * Copyright (c) 2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.vorto.repository.importer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.log4j.Logger;
import org.eclipse.vorto.model.ModelType;
import org.eclipse.vorto.repository.account.IUserAccountService;
import org.eclipse.vorto.repository.core.Attachment;
import org.eclipse.vorto.repository.core.FileContent;
import org.eclipse.vorto.repository.core.IModelRepository;
import org.eclipse.vorto.repository.core.IModelRepositoryFactory;
import org.eclipse.vorto.repository.core.IUserContext;
import org.eclipse.vorto.repository.core.ModelInfo;
import org.eclipse.vorto.repository.core.ModelResource;
import org.eclipse.vorto.repository.core.impl.ITemporaryStorage;
import org.eclipse.vorto.repository.core.impl.StorageItem;
import org.eclipse.vorto.repository.core.impl.parser.IModelParser;
import org.eclipse.vorto.repository.core.impl.parser.ModelParserFactory;
import org.eclipse.vorto.repository.core.impl.utils.DependencyManager;
import org.eclipse.vorto.repository.domain.Tenant;
import org.eclipse.vorto.repository.domain.User;
import org.eclipse.vorto.repository.tenant.ITenantService;
import org.eclipse.vorto.repository.tenant.ITenantUserService;
import org.eclipse.vorto.repository.web.core.exceptions.BulkUploadException;
import org.modeshape.common.collection.Collections;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Extend this class for Implementation of a special importer for Vorto
 *
 */
public abstract class AbstractModelImporter implements IModelImporter {

  public static final long TTL_TEMP_STORAGE_INSECONDS = 60 * 5;

  private static Logger logger = Logger.getLogger(AbstractModelImporter.class);

  @Autowired
  private ITemporaryStorage uploadStorage;

  @Autowired
  protected IModelRepositoryFactory modelRepoFactory;

  @Autowired
  private ITenantUserService tenantUserService;

  @Autowired
  protected IUserAccountService userRepository;

  @Autowired
  protected ITenantService tenantService;

  @Autowired
  protected ModelParserFactory modelParserFactory;

  private Set<String> supportedFileExtensions = new HashSet<>();

  protected static final String EXTENSION_ZIP = ".zip";

  public AbstractModelImporter(String modelTypeFileExtension, String... additionalExtensions) {
    if (handleZipUploads()) {
      supportedFileExtensions.add(EXTENSION_ZIP);
    }
    supportedFileExtensions.add(modelTypeFileExtension);
    supportedFileExtensions.addAll(Arrays.asList(additionalExtensions));
  }

  @Override
  public UploadModelResult upload(FileUpload fileUpload,  Optional<String> targetNamespace, IUserContext user) {
    if (!this.supportedFileExtensions.contains(fileUpload.getFileExtension())) {
      return new UploadModelResult(null, Arrays.asList(ValidationReport.invalid(null,
          "File type is invalid. Must be " + this.supportedFileExtensions)));
    }
    List<ValidationReport> reports = new ArrayList<ValidationReport>();
    if (handleZipUploads() && isZipFile(fileUpload)) {

      getUploadedFilesFromZip(fileUpload.getContent()).stream().filter(this::isSupported)
          .forEach(extractedFile -> {
            extractedFile = preProcess(extractedFile,targetNamespace);
            List<ValidationReport> validationResult = validate(extractedFile, user);
            postValidate(validationResult, user);
            reports.addAll(validationResult);
          });

    } else if (getSupportedFileExtensions().contains(fileUpload.getFileExtension())) {
      fileUpload = preProcess(fileUpload,targetNamespace);
      List<ValidationReport> validationResult = validate(fileUpload, user);
      postValidate(validationResult,user);
      reports.addAll(validationResult);

    }

    if (reports.size() == 0) {
      return new UploadModelResult(null, Arrays.asList(
          ValidationReport.invalid("Uploaded File does not contain any " + getKey() + " files.")));
    } else if (reports.stream().filter(report -> !report.isValid()).count() == 0) {
      return new UploadModelResult(createUploadHandle(fileUpload), reports);
    } else {
      return new UploadModelResult(createUploadHandle(fileUpload), reports);
    }
  }
  
  /**
   * Invoked before validation takes place
   * 
   * @param fileUpload uploaded file that needs to be validated
   * @param targetNamespace optional target namespace, that the to be validated file need to be converted to
   * @return
   */
  protected FileUpload preProcess(FileUpload fileUpload, Optional<String> targetNamespace) {
    return fileUpload;
  }

  private boolean isZipFile(FileUpload fileUpload) {
    return fileUpload.getFileExtension().equalsIgnoreCase(EXTENSION_ZIP);
  }

  protected boolean isSupported(FileUpload fileUpload) {
    return getSupportedFileExtensions().contains(fileUpload.getFileExtension());
  }

  protected Collection<FileUpload> getUploadedFilesFromZip(byte[] uploadedFile) {
    Collection<FileUpload> fileUploads = new ArrayList<FileUpload>();

    ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(uploadedFile));
    ZipEntry entry = null;

    try {
      while ((entry = zis.getNextEntry()) != null) {
        if (!entry.isDirectory()
            && !entry.getName().substring(entry.getName().lastIndexOf("/") + 1).startsWith(".")) {
          fileUploads.add(FileUpload.create(entry.getName(), copyStream(zis, entry)));
        }
      }
    } catch (IOException e) {
      throw new BulkUploadException("Problem while reading zip file during validation", e);
    }

    return fileUploads;
  }

  /**
   * Checks if the uploaded models already exist in the repository.
   * 
   * @param reports reports from the specific importer implementation
   * @param user currently performing the upload
   */
  protected void postValidate(List<ValidationReport> reports, IUserContext user) {
    reports.forEach(report -> {
      if (report.getModel() != null) {
        try {
          Optional<Tenant> tenant = tenantUserService.getTenantOfUserAndNamespace(
              user.getUsername(), report.getModel().getId().getNamespace());
          if (tenant.isPresent()) {
            IModelRepository modelRepository = modelRepoFactory
                .getRepository(tenant.get().getTenantId(), user.getAuthentication());
            ModelInfo m = modelRepository.getById(report.getModel().getId());
            if (m != null) {
              if (m.isReleased()) {
                report.setMessage(ValidationReport.ERROR_MODEL_ALREADY_RELEASED);
                report.setValid(false);
              } else {
                if (isAdmin(user) || m.getAuthor().equals(user.getUsername())) {
                  report.setMessage(ValidationReport.WARNING_MODEL_ALREADY_EXISTS);
                  report.setValid(true);
                } else {
                  report.setMessage(ValidationReport.ERROR_MODEL_ALREADY_EXISTS);
                  report.setValid(false);
                }
              }
            }
          }
        } catch (Exception e) {
          logger.error("Error while validating the model " + report.getModel().getId(), e);
          report.setMessage(new StatusMessage(
              "Internal error while trying to import model [" + report.getModel().getId() + "]",
              MessageSeverity.WARNING));
          report.setValid(false);
        }
      }

    });
  }

  private boolean isAdmin(IUserContext userContext) {
    User user = getUserRepository().getUser(userContext.getUsername());
    return user != null && (user.isSysAdmin(userContext.getTenant()));
  }

  private static byte[] copyStream(ZipInputStream in, ZipEntry entry) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      int size;
      byte[] buffer = new byte[2048];

      BufferedOutputStream bos = new BufferedOutputStream(out);

      while ((size = in.read(buffer, 0, buffer.length)) != -1) {
        bos.write(buffer, 0, size);
      }

      bos.flush();
      bos.close();
    } catch (IOException e) {
      throw new BulkUploadException("IOException while copying stream to ZipEntry", e);
    }

    return out.toByteArray();
  }

  private String createUploadHandle(FileUpload fileUpload) {
    final String handleId = UUID.randomUUID().toString();
    return this.uploadStorage.store(handleId, fileUpload, TTL_TEMP_STORAGE_INSECONDS).getKey();
  }

  @Override
  public List<ModelInfo> doImport(String uploadHandleId, IUserContext user) {
    StorageItem uploadedItem = this.uploadStorage.get(uploadHandleId);

    if (uploadedItem == null) {
      throw new ModelImporterException(
          "No uploaded file found for handleId '" + uploadHandleId + "'");
    }

    List<ModelInfo> importedModels = new ArrayList<>();

    try {

      if (handleZipUploads() && isZipFile(uploadedItem.getValue())) {

        getUploadedFilesFromZip(uploadedItem.getValue().getContent()).stream()
            .forEach(extractedFile -> {
              List<ModelResource> resources = this.convert(extractedFile, user);
              importedModels.addAll(sortAndSaveToRepository(resources, extractedFile, user));
            });

      } else {
        List<ModelResource> resources = this.convert(uploadedItem.getValue(), user);
        importedModels.addAll(sortAndSaveToRepository(resources, uploadedItem.getValue(), user));
      }
    } finally {
      this.uploadStorage.remove(uploadHandleId);
    }

    return importedModels;
  }

  private List<ModelInfo> sortAndSaveToRepository(List<ModelResource> resources,
      FileUpload extractedFile, IUserContext user) {
    List<ModelInfo> savedModels = new ArrayList<ModelInfo>();
    DependencyManager dm = new DependencyManager();
    for (ModelResource resource : resources) {
      dm.addResource(resource);
    }

    dm.getSorted().stream().forEach(resource -> {
      try {
        Optional<Tenant> tenant = tenantUserService.getTenantOfUserAndNamespace(user.getUsername(),
            resource.getId().getNamespace());
        if (tenant.isPresent()) {
          IModelRepository modelRepository =
              modelRepoFactory.getRepository(tenant.get().getTenantId(), user.getAuthentication());
          ModelInfo importedModel = modelRepository.save(resource.getId(),
              ((ModelResource) resource).toDSL(), createFileName(resource), user);
          savedModels.add(importedModel);
          postProcessImportedModel(importedModel,
              new FileContent(extractedFile.getFileName(), extractedFile.getContent()), user);
        } else {
          throw new ModelImporterException("User not authorized in tenant ");
        }
      } catch (Exception e) {
        e.printStackTrace();
        throw new ModelImporterException("Problem importing model", e);
      }
    });

    return savedModels;
  }

  protected boolean handleZipUploads() {
    return true;
  }

  @Override
  public Set<String> getSupportedFileExtensions() {
    return Collections.unmodifiableSet(this.supportedFileExtensions);
  }

  protected ModelResource parseDSL(String fileName, byte[] content) {
    return parseDSL(fileName, content, java.util.Collections.emptyList());
  }

  protected ModelResource parseDSL(String fileName, byte[] content,
      Collection<FileContent> fileReferences) {
    IModelParser modelParser = modelParserFactory.getParser(fileName);
    if (!fileReferences.isEmpty()) {
      modelParser.setReferences(fileReferences.stream()
          .filter(file -> !file.getFileName().equals(fileName)).collect(Collectors.toList()));
    }
    return (ModelResource) modelParser.parse(new ByteArrayInputStream(content));
  }

  protected ModelResource parseDSL(ModelType type, byte[] content) {
    return parseDSL("model" + type.getExtension(), content, java.util.Collections.emptyList());
  }

  protected ModelResource parseDSL(ModelType type, byte[] content,
      Collection<FileContent> fileReferences) {
    return parseDSL("model" + type.getExtension(), content, fileReferences);
  }

  protected String createFileName(ModelInfo resource) {
    return resource.getId().getName() + resource.getType().getExtension();
  }

  protected void postProcessImportedModel(ModelInfo importedModel, FileContent originalFileContent,
      IUserContext user) {
    Optional<Tenant> tenant = tenantUserService.getTenantOfUserAndNamespace(user.getUsername(),
        importedModel.getId().getNamespace());
    if (tenant.isPresent()) {
      IModelRepository modelRepository =
          modelRepoFactory.getRepository(tenant.get().getTenantId(), user.getAuthentication());
      modelRepository.attachFile(importedModel.getId(), originalFileContent, user,
          Attachment.TAG_IMPORTED);
      importedModel.setImported(true);
    }
  }

  /**
   * validates the given fileUpload content
   * 
   * @param content
   * @param fileName
   * @param user
   * @return
   */
  protected abstract List<ValidationReport> validate(FileUpload fileUpload, IUserContext user);

  /**
   * converts the given file upload content to Vorto DSL content
   * 
   * @param fileUpload
   * @param user
   * @return Vorto DSL content
   */
  protected abstract List<ModelResource> convert(FileUpload fileUpload, IUserContext user);

  public void setUploadStorage(ITemporaryStorage uploadStorage) {
    this.uploadStorage = uploadStorage;
  }

  public void setUserRepository(IUserAccountService userRepository) {
    this.userRepository = userRepository;
  }

  public void setModelParserFactory(ModelParserFactory modelParserFactory) {
    this.modelParserFactory = modelParserFactory;
  }

  public void setModelRepoFactory(IModelRepositoryFactory modelRepoFactory) {
    this.modelRepoFactory = modelRepoFactory;
  }

  public void setTenantUserService(ITenantUserService tenantUserService) {
    this.tenantUserService = tenantUserService;
  }

  public void setTenantService(ITenantService tenantService) {
    this.tenantService = tenantService;
  }

  public ITemporaryStorage getUploadStorage() {
    return uploadStorage;
  }

  public IUserAccountService getUserRepository() {
    return userRepository;
  }

  public ModelParserFactory getModelParserFactory() {
    return modelParserFactory;
  }

  public IModelRepositoryFactory getModelRepoFactory() {
    return modelRepoFactory;
  }

  public ITenantUserService getTenantUserService() {
    return tenantUserService;
  }

  public ITenantService getTenantService() {
    return tenantService;
  }
}
