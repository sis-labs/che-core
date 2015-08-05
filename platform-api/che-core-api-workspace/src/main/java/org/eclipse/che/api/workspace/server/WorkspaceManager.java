/*******************************************************************************
 * Copyright (c) 2012-2015 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.api.workspace.server;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;

import org.eclipse.che.api.core.BadRequestException;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.workspace.RuntimeWorkspace;
import org.eclipse.che.api.core.model.workspace.UsersWorkspace;
import org.eclipse.che.api.core.model.workspace.WorkspaceConfig;
import org.eclipse.che.api.core.model.workspace.WorkspaceState;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.workspace.server.model.impl.UsersWorkspaceImpl;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceStateImpl;
import org.eclipse.che.api.workspace.server.spi.WorkspaceDao;
import org.eclipse.che.api.workspace.shared.dto.event.WorkspaceStatusEvent;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.lang.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.eclipse.che.api.core.model.workspace.WorkspaceState.WorkspaceStatus.STOPPED;
import static org.eclipse.che.api.workspace.shared.dto.event.WorkspaceStatusEvent.EventType.ERROR;
import static org.eclipse.che.api.workspace.shared.dto.event.WorkspaceStatusEvent.EventType.RUNNING;
import static org.eclipse.che.api.workspace.shared.dto.event.WorkspaceStatusEvent.EventType.STARTING;
import static org.eclipse.che.api.workspace.shared.dto.event.WorkspaceStatusEvent.EventType.STOPPING;
import static org.eclipse.che.commons.lang.NameGenerator.generate;
import static org.eclipse.che.dto.server.DtoFactory.newDto;

/**
 * Facade for Workspace related operations
 *
 * @author gazarenkov
 * @author Alexander Garagatyi
 */
@Singleton
public class WorkspaceManager {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceService.class);

    /* should contain [3, 20] characters, first and last character is letter or digit, available characters {A-Za-z0-9.-_}*/
    private static final Pattern WS_NAME = Pattern.compile("[\\w][\\w\\.\\-]{1,18}[\\w]");

    private final WorkspaceDao             workspaceDao;
    private final RuntimeWorkspaceRegistry workspaceRegistry;
    private final EventService             eventService;
    private final ExecutorService          executor;

    private WorkspaceHooks hooks = WorkspaceHooks.NOOP_WORKSPACE_HOOKS;

    @Inject
    public WorkspaceManager(WorkspaceDao workspaceDao, RuntimeWorkspaceRegistry workspaceRegistry, EventService eventService) {
        this.workspaceDao = workspaceDao;
        this.workspaceRegistry = workspaceRegistry;
        this.eventService = eventService;

        executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("WorkspaceManager-%d").setDaemon(true).build());
    }

    @Inject(optional = true)
    public void setHooks(WorkspaceHooks hooks) {
        this.hooks = hooks;
    }

    /**
     * Get states of all user's workspaces
     *
     * @param owner
     *         id of the owner of workspace
     * @return list of {@link WorkspaceState}
     * @throws ServerException
     *         if any error occurs
     */
    public List<WorkspaceState> getWorkspacesStates(String owner) throws ServerException, BadRequestException {
        requiredNotNull(owner, "Workspace owner");

        final List<RuntimeWorkspace> runtimeWorkspaces = workspaceRegistry.getList(owner);
        final List<UsersWorkspace> usersWorkspaces = workspaceDao.getList(owner);
        Map<String, WorkspaceState> workspacesStates = new HashMap<>();
        for (RuntimeWorkspace runtimeWorkspace : runtimeWorkspaces) {
            workspacesStates.put(runtimeWorkspace.getId(),
                                 new WorkspaceStateImpl(runtimeWorkspace, runtimeWorkspace.isTemporary(), runtimeWorkspace.getStatus()));
        }
        for (UsersWorkspace usersWorkspace : usersWorkspaces) {
            if (!workspacesStates.containsKey(usersWorkspace.getId())) {
                workspacesStates.put(usersWorkspace.getId(), new WorkspaceStateImpl(usersWorkspace, false, STOPPED));
            }
        }

        return new LinkedList<>(workspacesStates.values());
    }

    public WorkspaceState startWorkspaceById(String workspaceId, String envName) throws NotFoundException, ServerException, BadRequestException {
        requiredNotNull(workspaceId, "Workspace id");

        final UsersWorkspace usersWorkspace = workspaceDao.get(workspaceId);
        return startWorkspace(usersWorkspace, envName, false);
    }

    public WorkspaceState startWorkspaceByName(String workspaceName, String owner)
            throws NotFoundException, ServerException, BadRequestException {
        requiredNotNull(workspaceName, "Workspace name");
        requiredNotNull(owner, "Workspace owner");

        final UsersWorkspace usersWorkspace = workspaceDao.get(workspaceName, owner);
        return startWorkspace(usersWorkspace, null, false);
    }

    // TODO should we store temp workspaces and where?
    public WorkspaceState startTemporaryWorkspace(WorkspaceConfig workspaceConfig, final String accountId)
            throws ServerException, BadRequestException, ForbiddenException, NotFoundException {
        final UsersWorkspace workspace = validateWorkspace(workspaceConfig);

        hooks.beforeCreate(workspace, accountId);

        final WorkspaceState workspaceState = startWorkspace(workspace, null, true);

        // TODO when this code should be called for temp workspaces
        hooks.afterCreate(workspace, accountId);

        LOG.info("EVENT#workspace-created# WS#{}# WS-ID#{}# USER#{}# TEMP#true#", workspace.getName(), workspace.getId(),
                 EnvironmentContext.getCurrent().getUser().getId());

        return workspaceState;
    }

    WorkspaceState startWorkspace(final UsersWorkspace usersWorkspace, final String envName, final boolean temp) {

        WorkspaceState workspaceState = new WorkspaceStateImpl(usersWorkspace, temp, WorkspaceState.WorkspaceStatus.STARTING);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                eventService.publish(newDto(WorkspaceStatusEvent.class)
                                             .withEventType(STARTING)
                                             .withWorkspaceId(usersWorkspace.getId()));

                try {
                    workspaceRegistry.start(usersWorkspace, envName, temp);

                    eventService.publish(newDto(WorkspaceStatusEvent.class)
                                                 .withEventType(RUNNING)
                                                 .withWorkspaceId(usersWorkspace.getId()));

                } catch (ForbiddenException | NotFoundException | ServerException e) {
                    eventService.publish(newDto(WorkspaceStatusEvent.class)
                                                 .withEventType(ERROR)
                                                 .withWorkspaceId(usersWorkspace.getId())
                                                 .withError(e.getLocalizedMessage()));

                    LOG.error(e.getLocalizedMessage(), e);
                }
            }
        });
        return workspaceState;
    }

    public void stopWorkspace(String workspaceId) throws ServerException, NotFoundException, ForbiddenException, BadRequestException {
        requiredNotNull(workspaceId, "Workspace id");

        eventService.publish(newDto(WorkspaceStatusEvent.class)
                                     .withEventType(STOPPING)
                                     .withWorkspaceId(workspaceId));

        workspaceRegistry.stop(workspaceId);

        eventService.publish(newDto(WorkspaceStatusEvent.class)
                                     .withEventType(WorkspaceStatusEvent.EventType.STOPPED)
                                     .withWorkspaceId(workspaceId));
    }

    public UsersWorkspace createWorkspace(final WorkspaceConfig workspaceConfig, final String accountId)
            throws NotFoundException, ForbiddenException, ServerException, BadRequestException, ConflictException {

        final UsersWorkspace workspace = validateWorkspace(workspaceConfig);

        hooks.beforeCreate(workspace, accountId);

        UsersWorkspace newWorkspace = workspaceDao.create(workspace);

        hooks.afterCreate(workspace, accountId);

        LOG.info("EVENT#workspace-created# WS#{}# WS-ID#{}# USER#{}#", workspace.getName(), workspace.getId(),
                 EnvironmentContext.getCurrent().getUser().getId());

        return newWorkspace;
    }

    private UsersWorkspace validateWorkspace(final WorkspaceConfig workspaceConfig)
            throws BadRequestException, ForbiddenException, ServerException {
        requiredNotNull(workspaceConfig, "Workspace config");
        requiredNotNull(workspaceConfig.getDefaultEnvironment(), "Workspace default environment");
        requiredNotNull(workspaceConfig.getEnvironments(), "Workspace default environment configuration");
        requiredNotNull(workspaceConfig.getEnvironment(workspaceConfig.getDefaultEnvironment()),
                        "Workspace default environment configuration");

        validateAttributes(workspaceConfig.getAttributes());

        final UsersWorkspaceImpl workspace = UsersWorkspaceImpl.from(workspaceConfig)
                                                               .setId(generateWorkspaceId())
                                                               .setOwner(getCurrentUserId());

        if (Strings.isNullOrEmpty(workspace.getName())) {
            workspace.setName(generateWorkspaceName());
        } else {
            validateName(workspaceConfig.getName());
        }

        return workspace;
    }

    public UsersWorkspace updateWorkspace(String workspaceId, final WorkspaceConfig workspace)
            throws ConflictException, ServerException, BadRequestException, NotFoundException, ForbiddenException {

        requiredNotNull(workspace, "Workspace config");
        requiredNotNull(workspace.getDefaultEnvironment(), "Workspace default environment");
        requiredNotNull(workspace.getEnvironments(), "Workspace default environment configuration");
        requiredNotNull(workspace.getEnvironment(workspace.getDefaultEnvironment()),
                        "Workspace default environment configuration");

        validateName(workspace.getName());
        validateAttributes(workspace.getAttributes());

        final UsersWorkspace currentWorkspace = workspaceDao.get(workspaceId);
        UsersWorkspace newWorkspace = UsersWorkspaceImpl.from(workspace)
                                                       .setId(currentWorkspace.getId())
                                                       .setOwner(currentWorkspace.getOwner());

        UsersWorkspace updated = workspaceDao.update(newWorkspace);

        LOG.info("EVENT#workspace-updated# WS#{}# WS-ID#{}#", updated.getName(), updated.getId());

        return updated;

    }

    public void removeWorkspace(String workspaceId) throws ConflictException, ServerException, NotFoundException, BadRequestException {
        requiredNotNull(workspaceId, "Workspace id");

        try {
            workspaceRegistry.get(workspaceId);

            throw new ConflictException("Can't remove running workspace " + workspaceId);
        } catch (NotFoundException e) {
            workspaceDao.remove(workspaceId);

            hooks.afterRemove(workspaceId);

            LOG.info("EVENT#workspace-remove# WS-ID#{}#", workspaceId);
        }
    }

    public UsersWorkspace getWorkspace(String workspaceId) throws NotFoundException, ServerException, BadRequestException {
        requiredNotNull(workspaceId, "Workspace id");

        return workspaceDao.get(workspaceId);
    }

    public RuntimeWorkspace getRuntimeWorkspace(String workspaceId) throws BadRequestException, NotFoundException, ServerException {
        requiredNotNull(workspaceId, "Workspace id");

        return workspaceRegistry.get(workspaceId);
    }

    /*******************************/

    private void validateName(String workspaceName) throws BadRequestException {
        if (Strings.isNullOrEmpty(workspaceName)) {
            throw new BadRequestException("Workspace name required");
        }
        if (!WS_NAME.matcher(workspaceName).matches()) {
            throw new BadRequestException("Incorrect workspace name, it should be between 3 to 20 characters and may contain digits, " +
                                          "latin letters, underscores, dots, dashes and should start and end only with digits, " +
                                          "latin letters or underscores");
        }
    }

    private String getCurrentUserId() {
        return EnvironmentContext.getCurrent().getUser().getId();
    }

    /**
     * Checks object reference is not {@code null}
     *
     * @param object
     *         object reference to check
     * @param subject
     *         used as subject of exception message "{subject} required"
     * @throws org.eclipse.che.api.core.BadRequestException
     *         when object reference is {@code null}
     */
    private void requiredNotNull(Object object, String subject) throws BadRequestException {
        if (object == null) {
            throw new BadRequestException(subject + " required");
        }
    }

    /**
     * Validates attribute name.
     *
     * @param attributeName
     *         attribute name to check
     * @throws org.eclipse.che.api.core.ConflictException
     *         when attribute name is {@code null}, empty or it starts with "codenvy"
     */
    // TODO rename restricted attribute suffix to 'che:'
    private void validateAttributeName(String attributeName) throws ForbiddenException {
        if (attributeName == null || attributeName.isEmpty() || attributeName.toLowerCase().startsWith("codenvy")) {
            throw new ForbiddenException(String.format("Attribute name '%s' is not valid", attributeName));
        }
    }

    private void validateAttributes(Map<String, String> attributes) throws ForbiddenException {
        if (attributes != null) {
            for (String attributeName : attributes.keySet()) {
                validateAttributeName(attributeName);
            }
        }
    }

    /**
     * Generates workspace name based on current user email.
     * Generating process is simple, assuming we have user with email user@codenvy.com,
     * then first time we will check for workspace with name equal to "user" and if it is free
     * it will be returned, but if it is reserved then  number suffix will be added to the end of "user" name
     * and it will be checked again until free workspace name is not found.
     */
    private String generateWorkspaceName() throws ServerException {
        //should be email
        String userName = currentUser().getName();
        int atIdx = userName.indexOf('@');
        //if username contains email then fetch part before '@'
        if (atIdx != -1) {
            userName = userName.substring(0, atIdx);
        }
        //search first workspace name which is free
        int suffix = 2;
        String workspaceName = userName;
        while (workspaceExists(workspaceName)) {
            workspaceName = userName + suffix++;
        }
        return workspaceName;
    }

    private String generateWorkspaceId() {
        return generate("workspace", Constants.ID_LENGTH);
    }

    private boolean workspaceExists(String name) throws ServerException {
        try {
            workspaceDao.get(name);
        } catch (NotFoundException nfEx) {
            return false;
        }
        return true;
    }

    private org.eclipse.che.commons.user.User currentUser() {
        return EnvironmentContext.getCurrent().getUser();
    }
}