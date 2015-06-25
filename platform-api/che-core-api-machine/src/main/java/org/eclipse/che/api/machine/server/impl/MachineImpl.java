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
package org.eclipse.che.api.machine.server.impl;

import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.util.LineConsumer;
import org.eclipse.che.api.machine.server.exception.MachineException;
import org.eclipse.che.api.machine.server.spi.Instance;
import org.eclipse.che.api.machine.server.spi.InstanceMetadata;
import org.eclipse.che.api.machine.server.spi.InstanceProcess;
import org.eclipse.che.api.machine.shared.Machine;
import org.eclipse.che.api.machine.shared.MachineState;
import org.eclipse.che.api.machine.shared.ProjectBinding;
import org.eclipse.che.api.machine.shared.Server;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Provides machine's operations
 *
 * @author andrew00x
 */
public class MachineImpl implements Machine {
    private final String              id;
    private final String              type;
    private final String              owner;
    private final LineConsumer        machineLogsOutput;
    private final Set<ProjectBinding> projectBindings;
    private final String              workspaceId;
    private final boolean             isWorkspaceBound;
    private final String              displayName;

    private Instance     instance;
    private MachineState state;

    public MachineImpl(String id,
                       String type,
                       String workspaceId,
                       String owner,
                       LineConsumer machineLogsOutput,
                       boolean isWorkspaceBound,
                       String displayName) {
        this.id = id;
        this.type = type;
        this.owner = owner;
        this.machineLogsOutput = machineLogsOutput;
        this.workspaceId = workspaceId;
        this.isWorkspaceBound = isWorkspaceBound;
        this.displayName = displayName;
        projectBindings = new CopyOnWriteArraySet<>();
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getOwner() {
        return owner;
    }

    @Override
    public Set<ProjectBinding> getProjects() {
        return projectBindings;
    }

    @Override
    public InstanceMetadata getMetadata() throws MachineException {
        if (instance != null) {
            return instance.getMetadata();
        }
        throw new MachineException("Instance of machine is not ready yet");
    }

    @Override
    public String getWorkspaceId() {
        return workspaceId;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Map<String, Server> getServers() throws MachineException {
        return getMetadata().getServers();
    }

    @Override
    public boolean isWorkspaceBound() {
        return isWorkspaceBound;
    }

    public synchronized MachineState getState() {
        return state;
    }

    public ProcessImpl getProcess(int pid) throws NotFoundException, MachineException {
        final Instance myInstance = getInstance();
        if (myInstance == null) {
            throw new MachineException(String.format("Machine %s is not ready to perform this action", id));
        }
        return new ProcessImpl(myInstance.getProcess(pid));
    }

    public List<ProcessImpl> getProcesses() throws MachineException {
        final Instance myInstance = getInstance();
        if (myInstance == null) {
            throw new MachineException(String.format("Machine %s is not ready to perform this action", id));
        }
        final List<InstanceProcess> instanceProcesses = myInstance.getProcesses();
        final List<ProcessImpl> processes = new LinkedList<>();
        for (InstanceProcess instanceProcess : instanceProcesses) {
            processes.add(new ProcessImpl(instanceProcess));
        }
        return processes;
    }

    public LineConsumer getMachineLogsOutput() {
        return machineLogsOutput;
    }

    public synchronized void setState(MachineState state) {
        this.state = state;
    }

    public synchronized Instance getInstance() {
        return instance;
    }

    public synchronized void setInstance(Instance instance) {
        this.instance = instance;
    }

    /**
     * Binds project to machine
     */
    public void bindProject(ProjectBinding project) throws MachineException {
        if (!isWorkspaceBound) {
            if (instance == null) {
                throw new MachineException(String.format("Machine %s is not ready to bind the project", id));
            }

            instance.bindProject(workspaceId, project);

            this.projectBindings.add(project);
        }
    }

    /**
     * Unbinds project from machine
     */
    public void unbindProject(ProjectBinding project) throws MachineException, NotFoundException {
        if (!isWorkspaceBound) {
            if (instance == null) {
                throw new MachineException(String.format("Machine %s is not ready to unbind the project", id));
            }

            for (ProjectBinding projectBinding : projectBindings) {
                if (projectBinding.getPath().equals(project.getPath())) {
                    instance.unbindProject(workspaceId, project);
                    this.projectBindings.remove(project);
                    return;
                }
            }
            throw new NotFoundException(String.format("Binding of project %s in machine %s not found", project.getPath(), id));
        }
    }
}