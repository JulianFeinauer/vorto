/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
define(["../../init/appController"], function (repositoryControllers) {

  repositoryControllers.controller("namespaceUserManagementController",
      ["$rootScope", "$scope", "$http", "$uibModal", "$uibModalInstance",
        "namespace", "dialogConfirm",
        function ($rootScope, $scope, $http, $uibModal, $uibModalInstance,
            namespace, dialogConfirm) {

          $scope.namespace = namespace;
          $scope.isRetrievingNamespaceUsers = false;
          $scope.namespaceUsers = [];

          $scope.cancel = function () {
            $uibModalInstance.dismiss("Canceled.");
          };

          $scope.$on("USER_CONTEXT_UPDATED", function (evt, data) {

          });

          $scope.getNamespaceUsers = function (namespacename) {
            $scope.isRetrievingNamespaceUsers = true;
            $http.get("./rest/namespaces/" + namespacename + "/users")
            .then(
                function (result) {
                  $scope.isRetrievingNamespaceUsers = false;
                  $scope.namespaceUsers = result.data;
                },
                function (reason) {
                  $scope.isRetrievingNamespaceUsers = false;
                  // TODO : handling of failures
                }
            );
          };

          $scope.getNamespaceUsers($scope.namespace.name);

          $scope.newUser = function () {
            return {
              edit: false,
              username: "",
              roleModelCreator: false,
              roleModelPromoter: false,
              roleModelReviewer: false,
              roleModelPublisher: false,
              roleUser: true,
              roleAdmin: false
            };
          };

          $scope.editableUser = function (user) {
            return {
              edit: true,
              userId: user.userId,
              roleModelCreator: user.roles.includes("MODEL_CREATOR"),
              roleModelPromoter: user.roles.includes("MODEL_PROMOTER"),
              roleModelReviewer: user.roles.includes("MODEL_REVIEWER"),
              roleModelPublisher: user.roles.includes("MODEL_PUBLISHER"),
              roleUser: user.roles.includes("USER"),
              roleAdmin: user.roles.includes("TENANT_ADMIN")
            };
          };

          $scope.createOrUpdateUser = function (user, namespace) {
            $scope.mode = user.edit ? "Update" : "Add";
            var modalInstance = $uibModal.open({
              animation: true,
              templateUrl: "webjars/repository-web/dist/partials/admin/createOrUpdateUser.html",
              size: "md",
              controller: "createOrUpdateUserController",
              resolve: {
                user: function () {
                  return user;
                },
                namespace: function () {
                  return $scope.namespace;
                }
              }
            });

            modalInstance.result.finally(function (result) {
              $scope.getNamespaceUsers($scope.namespace.name);
              $rootScope.init();
            });
          };

          $scope.deleteUser = function (user) {
            var dialog = dialogConfirm($scope,
                "Are you sure you want to remove user '" + user.userId + "'?",
                ["Confirm", "Cancel"]);

            dialog.setCallback("Confirm", function () {
              $http
              .delete("./rest/namespaces/" + $scope.namespace.name + "/users/"
                  + user.userId)
              .then(
                  function (result) {
                    $scope.getNamespaceUsers($scope.namespace.name);
                  },
                  function (reason) {
                    // TODO : Show error on window
                  }
              );
            });

            dialog.run();
          };

          $scope.hasUserRole = function (role, roles) {
            return roles.includes(role);
          }
        }
      ]);

  repositoryControllers.controller("createOrUpdateUserController",
      ["$rootScope", "$scope", "$uibModal", "$uibModalInstance", "$http",
        "user", "namespace",
        function ($rootScope, $scope, $uibModal, $uibModalInstance, $http, user,
            namespace) {

          $scope.mode = user.edit ? "Update" : "Add";
          $scope.user = user;
          $scope.namespace = namespace;
          $scope.isCurrentlyAddingOrUpdating = false;
          $scope.errorMessage = "";
          $scope.selectedAuthenticationProviderId = null;
          $scope.userPartial = "";
          $scope.selectedUser = null;
          $scope.lastHighlightedUser = null;
          $scope.retrievedUsers = [];
          $scope.technicalUserSubject = null;

          $scope.selectUser = function (user) {
            if (user) {
              $scope.selectedUser = user;
              document.getElementById(
                  'userId').value = $scope.selectedUser.userId;
            }
            $scope.retrievedUsers = [];
          }

          $scope.highlightUser = function (user) {
            let list = document.getElementById("userDropDown");
            let element = document.getElementById(user.userId);
            if (list && element) {
              element.style.backgroundColor = '#7fc6e7';
              element.style.color = '#ffffff';
              list.scrollTop = element.offsetTop - element.clientHeight;
              $scope.lastHighlightedUser = user;
            }
          }

          $scope.selectOtherUser = function (event, selected) {
            let length = $scope.retrievedUsers.length;
            let key = event.key;
            if (length == 1 && key !== 'Enter') {
              $scope.highlightUser($scope.retrievedUsers[0]);
              return;
            }
            let nextOrPrevious = null;
            if (key === 'ArrowUp') {
              nextOrPrevious = false;
            } else if (key === 'ArrowDown') {
              nextOrPrevious = true;
            } else if (key === 'Enter') {
              if ($scope.lastHighlightedUser) {
                $scope.selectUser($scope.lastHighlightedUser);
                return;
              }
            }
            if ($scope.retrievedUsers && nextOrPrevious !== null) {
              // if this is invoked from the input text, no namespace is selected
              let nextIndex = nextOrPrevious ? 0 : length - 1;
              if (selected) {
                let selectedIndex = $scope.retrievedUsers.indexOf(selected);
                // increment/decrement
                nextIndex = nextOrPrevious ? selectedIndex + 1
                    : selectedIndex - 1;
                // handle rotation
                if (nextIndex >= length) {
                  nextIndex = 0;
                } else if (nextIndex < 0) {
                  nextIndex = length - 1;
                }
              }
              // highlighting target list element
              $scope.highlightUser($scope.retrievedUsers[nextIndex]);
              // unhighlighting previously highlighted element - forward
              if (nextOrPrevious) {
                $scope.unhighlightUser(
                    $scope.retrievedUsers[nextIndex > 0 ? nextIndex - 1 : length
                        - 1]);
              } else {
                $scope.unhighlightUser(
                    $scope.retrievedUsers[nextIndex < length - 1 ? nextIndex + 1
                        : 0]);
              }
            }
          }

          $scope.unhighlightUser = function (user) {
            let element = document.getElementById(user.userId);
            if (element) {
              element.style.backgroundColor = 'initial';
              element.style.color = 'initial';
            }
          }

          $scope.findUsers = function () {
            // only initiates user search if partial name is larger >= 3 characters
            // this is to prevent unmanageably large drop-downs
            if ($scope.userPartial && $scope.userPartial.length >= 3) {
              $http.get("./rest/accounts/search/" + $scope.userPartial)
              .then(
                  function (result) {
                    if (result.data) {
                      $scope.retrievedUsers = result.data;
                    } else {
                      $scope.retrievedUsers = [];
                      $scope.selectedUser = null;
                    }
                  },
                  function (error) {
                  }
              );
            } else {
              $scope.retrievedUsers = [];
              $scope.selectedUser = null;
            }
            $scope.toggleSubmitButton();
          };

          $scope.cancel = function () {
            $uibModalInstance.dismiss("Canceled.");
          };

          $scope.promptCreateNewTechnicalUser = function () {
            let modalInstance = $uibModal.open({
              animation: true,
              templateUrl: "webjars/repository-web/dist/partials/admin/createTechnicalUser.html",
              size: "md",
              controller: "createOrUpdateUserController",
              resolve: {
                user: function () {
                  return user;
                },
                namespace: function () {
                  return namespace;
                }
              }
            });

            modalInstance.result.finally(function (result) {
              $uibModalInstance.close($scope.user);
            });
          };

          $scope.createNewTechnicalUser = function () {
            $scope.isCurrentlyAddingOrUpdating = false;
            $http.post("./rest/namespaces/" + $scope.namespace.name + "/users",
                {
                  "userId": $scope.user.userId,
                  "roles": $scope.getRoles($scope.user),
                  "authenticationProviderId": $scope.selectedAuthenticationProviderId,
                  "subject": $scope.technicalUserSubject,
                  "isTechnicalUser": true
                })
            .then(
                function (result) {
                  $uibModalInstance.close($scope.user);
                },
                function (reason) {
                  $scope.errorMessage = "Creation of technical user " +
                      $scope.user.userId + " in namespace " +
                      $scope.namespace.name + " failed. ";
                }
            );
          };

          $scope.toggleSubmitButton = function () {
            let button = document.getElementById("submitButton");
            if (button) {
              button.disabled = !(
                  ($scope.user && $scope.user.userId)
                  ||
                  $scope.userPartial
                  ||
                  $scope.selectedUser
              );
            }
            return button && !button.disabled;
          }

          $scope.addOrUpdateUser = function () {
            // adds username to scope user by either using selected user from
            // drop-down if any, or using the string in user's input     box
            if ($scope.selectedUser) {
              $scope.user.userId = $scope.selectedUser.userId;
            } else if ($scope.userPartial) {
              $scope.user.userId = $scope.userPartial;
            }
            $scope.validate($scope.user, function (result) {
              if (result.valid) {
                $scope.isCurrentlyAddingOrUpdating = false;
                $http.put(
                    "./rest/namespaces/" + $scope.namespace.name + "/users", {
                      "userId": $scope.user.userId,
                      "roles": $scope.getRoles($scope.user)
                    })
                .then(
                    function (result) {
                      $uibModalInstance.close($scope.user);
                    },
                    function (reason) {
                      $scope.errorMessage = "You cannot change your own permissions.";
                    }
                );
              } else {
                $scope.promptCreateNewTechnicalUser();
              }
            });
          };

          $scope.getRoles = function (user) {
            var roles = [];
            if ($scope.user.roleModelCreator) {
              roles.push("ROLE_MODEL_CREATOR")
            }

            if ($scope.user.roleModelPromoter) {
              roles.push("ROLE_MODEL_PROMOTER")
            }

            if ($scope.user.roleModelReviewer) {
              roles.push("ROLE_MODEL_REVIEWER")
            }

            if ($scope.user.roleModelPublisher) {
              roles.push("ROLE_MODEL_PUBLISHER")
            }

            if ($scope.user.roleUser) {
              roles.push("ROLE_USER")
            }

            if ($scope.user.roleAdmin) {
              roles.push("ROLE_TENANT_ADMIN")
            }

            return roles;
          };

          $scope.validate = function (user, callback) {
            if (user.userId === undefined || user.userId.trim() === '') {
              callback({
                valid: false,
                errorMessage: "userId must not be null."
              });
              return;
            }

            $http.get("./rest/accounts/" + user.userId)
            .then(
                function (result) {
                  callback({valid: true});
                },
                function (reason) {
                  if (reason.status == 404) {
                    callback({
                      valid: false,
                      errorMessage: "User doesn't exist."
                    });
                  } else {
                    callback({
                      valid: false,
                      errorMessage: "Error while accessing the server."
                    });
                  }
                }
            );
          };
        }
      ]);
});
