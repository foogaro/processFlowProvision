/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.processFlow.knowledgeService;

import java.util.Map;
import java.util.List;

/**
 *
 *<b>Responsibilities</b>
 *<pre>
 * StatefulKnowledgeSession management
 *   - implementations of this interface manage the lifecycle of one or more org.drools.runtime.StatefulKnowledgeSession objects
 *   - these StatefulKnowledgeSession objects implement org.drools.runtime.process.ProcessRuntime
 *   - subsequently, this interface exposes various ProcessRuntime derived operations as an EJB 'service'
 *   - ksessionId state :
 *      - some of the methods exposed by this interface take both a 'processInstanceId' and a  'ksessionId' as a parameter
 *      - the 'ksessionId' is optional depending on several considerations:
 *          - for an implementation that only maintains one StatefulKnowledgeSession (ie: similar to CommandDelegate of jbpm5 gwt-console-server)
 *            the ksessionId is irrelevant
 *          - for an implementation that assigns a StatefulKnowledgeSession to a single process instance or a group of process instances,
 *            that implementation should maintain a mapping of ksessionIds to processInstanceIds.  If null is passed to any of the methods
 *            accepting a ksessionId, then the implementation should execute a lookup for ksessionId.
 *
 * KnowledgeBase management
 *   - implementations of this interface will typically manage the lifecycle of an org.drools.KnowledgeBase
 *   - an implementation will use this KnowledgeBase object to create/load the runtime StatefulKnowledgeSession objects
 *   - the KnowledgeBase object is a repository of all knowledge definitions to include :  rules, processes, functions and type models
 *   - this KnowledgeBase object is typically kept current by interacting with a remote BRMS guvnor service
 *   - the remote BRMS guvnor service is the actual 'system-of-record' of knowledge definitions
 *
 * Business Activity Monitoring (BAM) audit logging
 *   - implementations of this may or may not feed a BAM data warehouse of process instance events
 *</pre>
 */
public interface IKnowledgeSession extends IBaseKnowledgeSession {
    public static final String KNOWLEDGE_SESSION_SERVICE_JNDI = "ejb:/kie-services-remote/prodKSessionProxy!org.jboss.processFlow.knowledgeService.IKnowledgeSession";
    public static final String KNOWLEDGE_SERVICE_PROVIDER_URL = "org.jboss.processFlow.knowledgeService.KNOWLEDGE_SERVICE_PROVIDER_URL";
    public static final String SPACE_DELIMITED_PROCESS_EVENT_LISTENERS = "space.delimited.process.event.listeners";
    public static final String TASK_CLEAN_UP_PROCESS_EVENT_LISTENER_IMPL="task.clean.up.process.event.listener.impl";
    public static final String PROCESS_ID = "processid";
    public static final String PROCESS_NAME="processName";
    public static final String PROCESS_VERSION="processVersion";
    public static final String PACKAGE_NAME="packageName";
    public static final String PROCESS_INSTANCE_ID = "processInstanceId";
    public static final String PROCESS_INSTANCE_STATE = "processInstanceState";
    public static final String KSESSION_ID = "ksessionId";
    public static final String WORK_ITEM_ID = "workItemId";
    public static final String EMAIL = "Email";
    public static final String OPERATION_TYPE="operationType";
    public static final String COMPLETE_WORK_ITEM = "completeWorkItem";
    public static final String START_PROCESS_AND_RETURN_ID="startProcessAndReturnId";
    public static final String SIGNAL_EVENT="signalEvent";
    public static final String SIGNAL_TYPE="signalType";
    public static final String BPMN_FILE="bpmnFile";
    public static final String NODE_ID="nodeId";
    public static final String ASYNC_BAM_PRODUCER="org.jboss.processFlow.knowledgeService.AsyncBAMProducer";
    public static final String CHANGE_SET_URLS = "org.jboss.processFlow.space.delimited.change.set.urls";



    /**
     * Aborts the process instance with the given id.  If the process instance has been completed
     * (or aborted), or the process instance cannot be found, this method will throw an
     * <code>IllegalArgumentException</code>.
     *
     * @param id the id of the process instance
     * @param deploymentId
     */
    public void abortProcessInstance(Long processInstanceId, String deploymentId);
    

    /**
     *retrieve a list of all Process definition objects that the KnowledgeBase is currently aware of
     */
    public List<String> getProcessIds(String deploymentId) throws Exception ;

    
    public void setProcessInstanceVariables(Long pInstanceId, Map<String, Object> pVariables, String deploymentId);
    
    
    /**
     * printWorkItemHandlers
     * <pre>
     * returns a listing of registered workItemHandlers with knowledgeSessions
     * </pre>
     */
    public String printWorkItemHandlers(String deploymentId);
    
    /**
     *getActiveProcessInstances
     *<pre>
     *given an optional Map of query criteria, return a List of ProcessInstance objects
     *currently, only one type of query criteria is supported and is keyed by PROCESS_ID
     *NOTE:  org.jbpm.persistence.processinstance.ProcessInstanceInfo does not implement java.io.Serializable ... so don't invoke directly from an EJB client
     *</pre>
     */
    //public List<ProcessInstanceInfo> getActiveProcessInstances(Map<String,Object> queryCriteria);
    //public String printActiveProcessInstances(Map<String,Object> queryCriteria);

    //public SerializableProcessMetaData getProcess(String processId);
    //public void                     removeProcess(String processId);
    
    //public String                   printActiveProcessInstanceVariables(Long processInstanceId, Integer ksessionId);
    //public Map<String, Object>      getActiveProcessInstanceVariables(Long processInstanceId, Integer ksessionId);
    
    /**
     * for details, please see:  http://docs.jboss.org/jbpm/v5.1/userguide/ch05.html#d0e1768
     */
    //public void upgradeProcessInstance(long processInstanceId, String processId, Map<String, Long> nodeMapping);
}
