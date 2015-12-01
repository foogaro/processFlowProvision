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

import java.io.Serializable;
import java.io.StringWriter;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.Default;
import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.quartz.JobExecutionContext;
import org.drools.SystemEventListenerFactory;
import org.drools.KnowledgeBaseFactory;
import org.drools.base.MapGlobalResolver;
import org.drools.command.SingleSessionCommandService;
import org.drools.command.impl.CommandBasedStatefulKnowledgeSession;
import org.drools.event.rule.AgendaEventListener;
import org.drools.event.rule.WorkingMemoryEventListener;
import org.drools.event.process.ProcessCompletedEvent;
import org.drools.event.process.ProcessEventListener;
import org.drools.event.process.ProcessNodeLeftEvent;
import org.drools.event.process.ProcessNodeTriggeredEvent;
import org.drools.event.process.ProcessStartedEvent;
import org.drools.event.process.ProcessVariableChangedEvent;
import org.drools.io.*;
import org.drools.logger.KnowledgeRuntimeLogger;
import org.drools.logger.KnowledgeRuntimeLoggerFactory;
import org.drools.persistence.jpa.JPAKnowledgeService;
import org.drools.runtime.KnowledgeSessionConfiguration;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.Environment;
import org.drools.runtime.process.ProcessInstance;
import org.drools.runtime.process.WorkItemHandler;
import org.drools.runtime.rule.FactHandle;
import org.jbpm.process.core.context.variable.VariableScope;
import org.jbpm.process.instance.context.variable.VariableScopeInstance;
import org.jbpm.process.instance.timer.TimerInstance;
import org.jbpm.workflow.instance.impl.WorkflowProcessInstanceImpl;
import org.jbpm.workflow.instance.node.SubProcessNodeInstance;
import org.jbpm.task.admin.TaskCleanUpProcessEventListener;
import org.jbpm.task.admin.TasksAdmin;
import org.jbpm.workflow.instance.WorkflowProcessInstanceUpgrader;
import org.jboss.processFlow.knowledgeService.IKnowledgeSession;
import org.jboss.processFlow.util.CMTDisposeCommand;
import org.jboss.processFlow.util.GlobalQuartzJobHandle;
import org.mvel2.MVEL;


/**
 *<pre>
 *architecture
 *  - this singleton utilizes a 'processInstance per knowledgeSession' architecture
 *  - although the jbpm5 API technically allows for a StatefulKnowledgeSession to manage the lifecycle of multiple process instances,
 *      we choose not to have to deal with optimistic lock exception handling (in particular with the sessionInfo) during highly concurrent environments
 *
 *notes on Transactions
 *  - most publicly exposed methods in this singleton assumes a container managed trnx demarcation of REQUIRED
 *  - in some methods, bean managed transaction demarcation is used IOT dispose of the ksession *AFTER* the transaction has committed
 *  - otherwise, the method will fail due to implementation of JBRULES-1880
 *
 *      
 *ksession management
 *  - in this IKnowledgeSession implementation, a ksessionId is allocated to a process instance (and any subprocesses) for its entire lifecycle 
 *  - upon completion of a process instance, the ksessionId is made available again for a new process instance
 *  - this singleton utilizes two data structures, busySessions & availableSessions, to maintain which ksessionIds are available for reuse
 *  - a sessioninfo record in the jbpm database corresponds to a single StatefulKnowledgeSession
 *  - a sessioninfo record typically includes the state of :
 *          * timers
 *          * business rule data
 *          * business rule state
 *  - a sessioninfo record is never purged from the database ... in this implementation it is simply re-cycled for use by a new process instance
 *  - ksessionId state :
 *      - some of the public methods implemented by this bean take both a 'processInstanceId' and a 'ksessionId' as a parameter
 *      - for the purposes of this implementation, the 'ksessionId' is always optional 
 *          if null is passed to any of the methods accepting a ksessionid, then this implementation will query the jbpm5 task table
 *          to determine the mapping between processInstanceId and ksessionId
 *  - this implementation is ideal in a multi-thread, concurrent client environment where the following is either met or is acceptable:
 *      1)  process definitions do include rule data
 *      2)  from a performance perspective, it's critical that process instance lifecycle functions are executed in parallel rather than synchroneously
 *          NOTE:  see org.drools.persistence.SingleSessionCommandService.execute(...) function
 *
 *</pre>
 *
 *  22 Jan 2013:  various performance optimizations and general cleanup contributed by Michal Valach.  thank you!
 */

@ApplicationScoped
@Alternative
@Default
public class SessionPerPInstanceBean extends BaseKnowledgeSessionBean implements IKnowledgeSession {

    private static final String DASH = "-";
    private static final String TIMER_TRIGGERED="timerTriggered";
    private ConcurrentMap<Integer, KnowledgeSessionWrapper> kWrapperHash = new ConcurrentHashMap<Integer, KnowledgeSessionWrapper>();
    private Logger log = Logger.getLogger(SessionPerPInstanceBean.class);
    private IKnowledgeSessionPool sessionPool;

    @Inject
    private AsyncBAMProducerPool bamProducerPool;
    
/******************************************************************************
 **************        Singleton Lifecycle Management                     *********/
    @PostConstruct
    public void start() throws Exception {
        super.start();

        if (System.getProperty("org.jboss.processFlow.KnowledgeSessionPool") != null) {
            String clazzName = System.getProperty("org.jboss.processFlow.KnowledgeSessionPool");
            sessionPool = (IKnowledgeSessionPool) Class.forName(clazzName).newInstance();
        }
        else {
            sessionPool = new InMemoryKnowledgeSessionPool();
        }
        QuartzSchedulerService.start();
    }
  
    @PreDestroy 
    public void stop() throws Exception {
        log.info("stop");
        // JA Bride :  completely plagarized from David Ward in his org.jboss.internal.soa.esb.services.rules.DroolsResourceChangeService implementation

        // ORDER IS IMPORTANT!
        // 1) stop the scanner
        ResourceFactory.getResourceChangeScannerService().stop();

        // 2) stop the notifier
        //ResourceFactory.getResourceChangeNotifierService().stop();

         // 3) set the system event listener back to the original implementation
        SystemEventListenerFactory.setSystemEventListener(originalSystemEventListener);
        
        QuartzSchedulerService.stop();
    }

/******************************************************************************
 *************        StatefulKnowledgeSession Management               *********/
    
    /*
        - load a StatefulKnowledgeSession with an id recently freed during the 'after process completion' event
        - if no available sessions, then make a new StatefulKnowledgeSession
     */
    private StatefulKnowledgeSession getStatefulKnowledgeSession(String processId) {
        StatefulKnowledgeSession ksession = null;
        if(processId != null) {
            int sessionId = sessionPool.getAvailableSessionId();
            if(sessionId > 0) {
                ksession = loadStatefulKnowledgeSession(new Integer(sessionId));
            } else {
                ksession = makeStatefulKnowledgeSession();
            }
            sessionPool.markAsBorrowed(ksession.getId(), processId);
        } else {
            ksession = makeStatefulKnowledgeSession();
        }
        return ksession;
    }

    private StatefulKnowledgeSession loadStatefulKnowledgeSession(Integer sessionId) {
        if(kWrapperHash.containsKey(sessionId)) {
            //log.info("loadStatefulKnowledgeSession() found ksession in cache for ksessionId = " +sessionId);
            return kWrapperHash.get(sessionId).ksession;
        }
        
        //0) initialise knowledge base if it hasn't already been done so
        checkKAgentAndBaseHealth();

        //1) very important that a unique 'Environment' is created every time StatefulKnowledgeSession is loaded
        Environment ksEnv = createKnowledgeSessionEnvironment();

        KnowledgeSessionConfiguration ksConfig = KnowledgeBaseFactory.newKnowledgeSessionConfiguration(ksconfigProperties);

        // 2) instantiate new StatefulKnowledgeSession from old sessioninfo
        StatefulKnowledgeSession ksession = JPAKnowledgeService.loadStatefulKnowledgeSession(sessionId, kbase, ksConfig, ksEnv);
        return ksession;
    }

    /*
     *  disposeStatefulKnowledgeSessionAndExtras
     *<pre>
     *- disposes of a StatefulKnowledgeSession object currently in use
     *- NOTE:  can no longer dispose knowledge session within scope of a transaction due to side effects from fix for JBRULES-1880
     *</pre>
     */
    public void disposeStatefulKnowledgeSessionAndExtras(Integer sessionId) {
        try {
            KnowledgeSessionWrapper kWrapper = ((KnowledgeSessionWrapper)kWrapperHash.get(sessionId));
            if(kWrapper == null){
                log.error("disposeStatefulKnowledgeSessionAndExtras() no ksessionWrapper found with sessionId = "+sessionId);
                return;
            }
            
            kWrapper.dispose();
            kWrapperHash.remove(sessionId);
        } catch(RuntimeException x) {
            throw x;
        } catch(Exception x){
            throw new RuntimeException(x);
        }
    }

    private void addExtrasToStatefulKnowledgeSession(StatefulKnowledgeSession ksession) {
        
        addExtrasCommon(ksession);
        
        // 3)  add 'busySessions' ProcessEventListener to knowledgesession to assist in maintaining 'busySessions' state
        final ProcessEventListener busySessionsListener = new ProcessEventListener() {

            /* 
             * these process events are implemented as a 'stack pattern'
             * ie:  afterProcessStarted() event is the last event to be called
             * see org.jbpm.process.instance.ProcessRuntimeImpl.startProcessInstance(long processInstanceId) for details
            */
            public void afterProcessCompleted(ProcessCompletedEvent event) {
                StatefulKnowledgeSession ksession = (StatefulKnowledgeSession)event.getKnowledgeRuntime();
                ProcessInstance pInstance = event.getProcessInstance();
                org.drools.definition.process.Process droolsProcess = event.getProcessInstance().getProcess();
                if(sessionPool.isBorrowed(ksession.getId(), pInstance.getProcessId())) {
                    log.info("afterProcessCompleted()\tsessionId :  "+ksession.getId()+" : "+pInstance+" : pDefVersion = "+droolsProcess.getVersion()+" : session to be reused");
                    sessionPool.markAsReturned(ksession.getId());
                } else {
                    log.info("afterProcessCompleted()\tsessionId :  "+ksession.getId()+" : process : "+pInstance+" : pDefVersion = "+droolsProcess.getVersion());
                }
                
                // Thank you Duncan Doyle for the following:
                //Retract all the facts from the knowledge runtime.
                Collection<FactHandle> factHandles = ksession.getFactHandles();
                for (FactHandle nextFactHandle : factHandles) {
                    ksession.retract(nextFactHandle);
                }
                // Reset globals in the knowledge runtime.
                MapGlobalResolver globals = (MapGlobalResolver) ksession.getGlobals();
                Entry<String, Object>[] entries = globals.getGlobals();
                for (Entry<String, Object> nextEntry : entries) {
                    nextEntry.setValue(null);
                }
                
                // Clear Agenda
                ksession.getAgenda().clear();
            }

            public void beforeProcessStarted(ProcessStartedEvent event) {
            }

            /* 
                with a process with no wait state, this call-back method will actually get invoked AFTER the 'afterProcessCompleted' call back
                - if parent process, state = 1
                - if subprocess, state = 2
            */
            public void afterProcessStarted(ProcessStartedEvent event) {
                StatefulKnowledgeSession ksession = (StatefulKnowledgeSession)event.getKnowledgeRuntime();
                ProcessInstance pInstance = event.getProcessInstance();
                org.drools.definition.process.Process droolsProcess = event.getProcessInstance().getProcess();
                log.info("afterProcessStarted()\tsessionId :  "+ksession.getId()+" : "+pInstance+" : pDefVersion = "+droolsProcess.getVersion());
            }
            public void beforeProcessCompleted(ProcessCompletedEvent event) {
            }
            public void beforeNodeTriggered(ProcessNodeTriggeredEvent event) {
                if (event.getNodeInstance() instanceof SubProcessNodeInstance) {
                    StatefulKnowledgeSession ksession = (StatefulKnowledgeSession)event.getKnowledgeRuntime();
                    SubProcessNodeInstance spNode = (SubProcessNodeInstance)event.getNodeInstance();
                    org.drools.definition.process.Process droolsProcess = event.getProcessInstance().getProcess();
                    if(enableLog)
                        log.info("beforeNodeTriggered()\tsessionId :  "+ksession.getId()+" : sub-process : " + spNode.getNodeName()+" : pid: "+spNode.getProcessInstanceId()+" : pDefVersion = "+droolsProcess.getVersion());
                }
            }
            public void afterNodeTriggered(ProcessNodeTriggeredEvent event) {
                if (event.getNodeInstance() instanceof SubProcessNodeInstance) {
                    StatefulKnowledgeSession ksession = (StatefulKnowledgeSession)event.getKnowledgeRuntime();
                    org.drools.definition.process.Process droolsProcess = event.getProcessInstance().getProcess();
                    SubProcessNodeInstance spNode = (SubProcessNodeInstance)event.getNodeInstance();
                    if(enableLog)
                        log.info("afterNodeTriggered()\tsessionId :  "+ksession.getId()+" : sub-process : " + spNode.getNodeName()+" : pid: "+spNode.getProcessInstanceId()+" : pDefVersion = "+droolsProcess.getVersion());
                }
            }
            public void beforeNodeLeft(ProcessNodeLeftEvent event) {
            }
            public void afterNodeLeft(ProcessNodeLeftEvent event) {
            }
            public void beforeVariableChanged(ProcessVariableChangedEvent event) {
            }
            public void afterVariableChanged(ProcessVariableChangedEvent event) {
            }
        };
        ksession.addEventListener(busySessionsListener);

        // 4) register TaskCleanUpProcessEventListener
        //   NOTE:  need to ensure that task audit data has been pushed to BAM prior to this taskCleanUpProcessEventListener firing
        if(!StringUtils.isEmpty(taskCleanUpImpl) && taskCleanUpImpl.equals(TaskCleanUpProcessEventListener.class.getName())) {
            TasksAdmin adminObj = jtaTaskService.createTaskAdmin();
            TaskCleanUpProcessEventListener taskCleanUpListener = new TaskCleanUpProcessEventListener(adminObj);
            ksession.addEventListener(taskCleanUpListener);
        }

       
        // 5)  register any other process event listeners specified via configuration
        // TO_DO:  refactor using mvel. ie:  jbpm-gwt/jbpm-gwt-console-server/src/main/resources/default.session.template
        AsyncBAMProducer bamProducer= null;
        if(processEventListeners != null) {
            for(String peString : processEventListeners) {
                try {
                    Class peClass = Class.forName(peString);
                    ProcessEventListener peListener = (ProcessEventListener)peClass.newInstance();
                    if(IKnowledgeSession.ASYNC_BAM_PRODUCER.equals(peListener.getClass().getName()))
                        bamProducer = (AsyncBAMProducer)peListener;
                    ksession.addEventListener(peListener);
                } catch(Exception x) {
                    throw new RuntimeException(x);
                }
            }
        }
 
        // 6)  create a kWrapper object with optional bamProducer
        KnowledgeSessionWrapper kWrapper = new KnowledgeSessionWrapper(ksession, bamProducer);
        kWrapperHash.put(ksession.getId(), kWrapper);

        // 7)  add KnowledgeRuntimeLogger as per section 4.1.3 of jbpm5 user manual
        if(enableKnowledgeRuntimeLogger) {
            StringBuilder sBuilder = new StringBuilder();
            sBuilder.append(System.getProperty("jboss.server.log.dir"));
            sBuilder.append("/knowledgeRuntimeLogger-");
            sBuilder.append(ksession.getId());
            kWrapper.setKnowledgeRuntimeLogger(KnowledgeRuntimeLoggerFactory.newFileLogger(ksession, sBuilder.toString()));
        }

        SingleSessionCommandService ssCommandService = (SingleSessionCommandService) ((CommandBasedStatefulKnowledgeSession)ksession).getCommandService();
    }

    private StatefulKnowledgeSession loadStatefulKnowledgeSessionAndAddExtras(Integer sessionId) {
        StatefulKnowledgeSession ksession = loadStatefulKnowledgeSession(sessionId);
        addExtrasToStatefulKnowledgeSession(ksession);
        return ksession;
    }

    public String dumpSessionStatusInfo() {
        return sessionPool.dumpSessionStatusInfo();
    }

    public String dumpBAMProducerPoolInfo() {
        StringBuilder sBuilder = new StringBuilder("dumpBAMProducerPoolInfo()\n\tNumber Active = ");
        if(bamProducerPool != null) {
            sBuilder.append(bamProducerPool.getNumActive());
            sBuilder.append("\n\tNumber Idle = ");
            sBuilder.append(bamProducerPool.getNumIdle());
        } else {
            sBuilder.append("bamProducerPool is null.  most likely environment is not configured correctly for async logging of bam events from jbpm5 process engine");
        }
        return sBuilder.toString();
    }
    
    

 
/******************************************************************************
 *************              Process Instance Management              *********/
    
    /**
     *startProcessAndReturnId
     *<pre>
     *- this method will block until the newly created process instance either completes or arrives at a wait state
     *- at completion of the process instance (or arrival at a wait state), the StatefulKnowledgeSession will be disposed
     * - will return null if problems arise
     *</pre>
     */
    public Map<String, Object> startProcessAndReturnId(String processId, Map<String, Object> parameters) {
        StatefulKnowledgeSession ksession = null;
        StringBuilder sBuilder = new StringBuilder();
        Integer ksessionId = null;
        ProcessInstance pInstance = null;
        Map<String, Object> returnMap = new HashMap<String, Object>();
        try {
            ksession = getStatefulKnowledgeSession(processId);
            ksessionId = ksession.getId();
            addExtrasToStatefulKnowledgeSession(ksession);
            sBuilder.append("startProcessAndReturnId()\tsessionId :  "+ksessionId+" : process = "+processId);

            if(parameters != null) {
                pInstance = ksession.startProcess(processId, parameters);
            } else {
                pInstance = ksession.startProcess(processId);
            }

            // now always return back to client the latest (possibly modified) pInstance variables
            // thank you  Jano Kasarda
            Map<String, Object> variables = ((WorkflowProcessInstanceImpl) pInstance).getVariables();
            for (String key : variables.keySet()) {
                returnMap.put(key, variables.get(key));
            }
            returnMap.put(IKnowledgeSession.PROCESS_INSTANCE_ID, pInstance.getId());
            returnMap.put(IKnowledgeSession.PROCESS_INSTANCE_STATE, pInstance.getState());
            returnMap.put(IKnowledgeSession.KSESSION_ID, ksessionId);
            
            sessionPool.setProcessInstanceId(ksessionId, pInstance.getId());
        }catch(Throwable x){
            x.printStackTrace();
            return null;
        }finally {
            if(ksession != null){
                disposeStatefulKnowledgeSessionAndExtras(ksessionId);
            }
        }
        sBuilder.append(" : pInstanceId = "+pInstance.getId()+" : function (not necessarily pInstance) now completed.  check the jbpm_bam db for status of pInstance");
        log.info(sBuilder.toString());
        return returnMap;
    }

   

    public int signalEvent(String signalType, Object signalValue, Long processInstanceId, Integer ksessionId) {
        StatefulKnowledgeSession ksession = null;
        try {
            try {
                // always go to the database to ensure row-level pessimistic lock for each process instance
                ksessionId = sessionPool.getSessionId(processInstanceId);


                //due to ksession.dispose() needing to be outside trnx, ksessionId could still be temporarily in kWrapperHash 
                //want to avoid calling loadStatefulKnowledgeSessionAndExtras until ksessionId has been removed from kWrapperHash
                boolean goodToGo=true;
                for(int x=0; x < 10; x++){
                    if(kWrapperHash.containsKey(ksessionId)) {
                        log.info("signalEvent() found ksession in cache for ksessionId = " +ksessionId+" :  will sleep");
                        try {Thread.sleep(100);} catch(Exception t){t.printStackTrace();}
                        goodToGo = false;
                    }else {
                        goodToGo = true;
                        break;
                    }
                }
                if(!goodToGo)
                    throw new RuntimeException("signalEvent() the following ksession continues to be in use: "+ksessionId);


                ksession = this.loadStatefulKnowledgeSessionAndAddExtras(ksessionId);

                StringBuilder sBuilder = new StringBuilder("signalEvent() \n\tksession = "+ksessionId+"\n\tprocessInstanceId = "+processInstanceId+"\n\tsignalType="+signalType);
                // sometimes signalValue can be huge (as in if passing large JSON/xml strings )
                if(enableLog) {
                    sBuilder.append("\n\tsignalValue="+signalValue);
                }
                log.info(sBuilder.toString());

                ProcessInstance pInstance = ksession.getProcessInstance(processInstanceId);
                if(pInstance == null){
                    log.warn("signalEvent() not able to locate pInstance with id = "+processInstanceId+" : for sessionId = "+ksessionId);
                    return ProcessInstance.STATE_COMPLETED;
                }else {
                    pInstance.signalEvent(signalType, signalValue);
                    return pInstance.getState();
                }
            }finally {
                if(ksession != null)
                    disposeStatefulKnowledgeSessionAndExtras(ksessionId);
            }
        } catch(RuntimeException x) {
            log.error("signalEvent() exception thrown.  signalType = "+signalType+" : pInstanceId = "+processInstanceId+" : ksessionId ="+ksessionId);
            throw x;
        }catch(Exception x) {
            log.error("signalEvent() exception thrown.  signalType = "+signalType+" : pInstanceId = "+processInstanceId+" : ksessionId ="+ksessionId);
            throw new RuntimeException(x);
        }
    }

    public void abortProcessInstance(Long processInstanceId, Integer ksessionId) {
        StatefulKnowledgeSession ksession = null;
        try {
            try {
                if(ksessionId == null)
                    ksessionId = sessionPool.getSessionId(processInstanceId);

                ksession = loadStatefulKnowledgeSessionAndAddExtras(ksessionId);
                ksession.abortProcessInstance(processInstanceId);
            }finally {
                if(ksession != null)
                    disposeStatefulKnowledgeSessionAndExtras(ksessionId);
            }
        } catch(RuntimeException x) {
            throw x;
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
    }
    
    public void upgradeProcessInstance(long processInstanceId, String processId, Map<String, Long> nodeMapping) {
        StatefulKnowledgeSession ksession = null;
        Integer ksessionId = 0;
        try {
            try {
                ksessionId = sessionPool.getSessionId(processInstanceId);
                ksession = loadStatefulKnowledgeSessionAndAddExtras(ksessionId);
                WorkflowProcessInstanceUpgrader.upgradeProcessInstance(ksession, processInstanceId, processId, nodeMapping);
            }finally {
                if(ksession != null)
                    disposeStatefulKnowledgeSessionAndExtras(ksessionId);
            }
        } catch(Exception x) {
            throw new RuntimeException(x);
        }
    }
    
    public String printActiveProcessInstanceVariables(Long processInstanceId, Integer ksessionId) {
        Map<String,Object> vHash = null;
        try {
            try {
                if(ksessionId == null)
                    ksessionId = sessionPool.getSessionId(processInstanceId);

                vHash = getActiveProcessInstanceVariables(processInstanceId, ksessionId);
            }finally {
                disposeStatefulKnowledgeSessionAndExtras(ksessionId);
            }
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
        if(vHash.size() == 0)
            log.error("printActiveProcessInstanceVariables() no process instance variables for :\n\tprocessInstanceId = "+processInstanceId);
        
        StringWriter sWriter = null;
        try {
            sWriter = new StringWriter();
            ObjectMapper jsonMapper = new ObjectMapper();
            jsonMapper.writeValue(sWriter, vHash);
            return sWriter.toString();
        }catch(Exception x){
            throw new RuntimeException(x);
        }finally {
            if(sWriter != null) {
                try { sWriter.close();  }catch(Exception x){x.printStackTrace();}
            }
        }
    }
    
    public void setProcessInstanceVariables(Long processInstanceId, Map<String, Object> variables, Integer ksessionId) {
        try {
            try {
                if(ksessionId == null)
                    ksessionId = sessionPool.getSessionId(processInstanceId);

                StatefulKnowledgeSession ksession = loadStatefulKnowledgeSessionAndAddExtras(ksessionId);
                ProcessInstance processInstance = ksession.getProcessInstance(processInstanceId);
                if (processInstance != null) {
                    VariableScopeInstance variableScope = (VariableScopeInstance)((org.jbpm.process.instance.ProcessInstance) processInstance).getContextInstance(VariableScope.VARIABLE_SCOPE);
                    if (variableScope == null) {
                        throw new IllegalArgumentException("Could not find variable scope for process instance " + processInstanceId);
                    }
                    for (Map.Entry<String, Object> entry: variables.entrySet()) {
                        variableScope.setVariable(entry.getKey(), entry.getValue());
                    }
                } else {
                    throw new IllegalArgumentException("Could not find process instance " + processInstanceId);
                }
                
            }finally {
                disposeStatefulKnowledgeSessionAndExtras(ksessionId);
            }
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
    }
    
    
    // notifies process engine to complete a work item and continue execution of next node in process instance
    // can no longer dispose knowledge session within scope of this transaction due to side effects from fix for JBRULES-1880
    // subsequently, it's expected that a client will invoke 'disposeStatefulKnowledgeSessionAndExtras' after this JTA trnx has been committed
    public void completeWorkItem(Long workItemId, Map<String, Object> pInstanceVariables, Long pInstanceId, Integer ksessionId) {
        try {
            try {
                if(ksessionId == null)
                    ksessionId = sessionPool.getSessionId(pInstanceId);

                StatefulKnowledgeSession ksession = loadStatefulKnowledgeSessionAndAddExtras(ksessionId);
                ksession.getWorkItemManager().completeWorkItem(workItemId, pInstanceVariables);
            }finally {
                disposeStatefulKnowledgeSessionAndExtras(ksessionId);
            }
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
    }
    
    public Map<String, Object> getActiveProcessInstanceVariables(Long processInstanceId, Integer ksessionId) {
        if(ksessionId == null)
            ksessionId = sessionPool.getSessionId(processInstanceId);

        Map<String, Object> result = new HashMap<String, Object>();
        try {
            StatefulKnowledgeSession ksession = this.loadStatefulKnowledgeSessionAndAddExtras(ksessionId);
            ProcessInstance processInstance = ksession.getProcessInstance(processInstanceId);
            if (processInstance != null) {
                Map<String, Object> variables = ((WorkflowProcessInstanceImpl) processInstance).getVariables();
                if (variables == null) {
                    return new HashMap<String, Object>();
                }
                // filter out null values
                for (Map.Entry<String, Object> entry: variables.entrySet()) {
                    if (entry.getValue() != null) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            } else {
                log.error("getActiveProcessInstanceVariables() :  Could not find process instance " + processInstanceId);
            }
        }finally{
            this.disposeStatefulKnowledgeSessionAndExtras(ksessionId);
        }
        return result;
    }
    
   
    
    // implementation is expecting jContext parameter to be of type:  org.quartz.JobExecutionContext
    public int processJobExecutionContext(Serializable jContext) {
        JobExecutionContext qContext = (JobExecutionContext)jContext;
        GlobalQuartzJobHandle jHandle = (GlobalQuartzJobHandle)(qContext.getMergedJobDataMap().get(QuartzSchedulerService.TIMER_JOB_HANDLE));
        
        String jName = qContext.getJobDetail().getName();
        String[] details = StringUtils.split(jName, DASH);
        int sessionId = jHandle.getSessionId();
        String timerType = details[0];
        long period = jHandle.getInterval();
        try {
            if(QuartzSchedulerService.PROCESS_JOB.equals(timerType)){
                long pInstanceId = Long.parseLong(details[1]);
                long timerId = Long.parseLong(details[2]);
                log.info("processJobExecution() sessionId = "+sessionId+" : pInstanceId = "+pInstanceId+" : timerId = "+timerId);
                TimerInstance jbpmTimerInstance = new TimerInstance();
                jbpmTimerInstance.setId(timerId);
                /* A Timer node is set up with a delay and a period. 
                 * The delay specifies the amount of time to wait after node activation before triggering the timer the first time. 
                 * The period defines the time between subsequent trigger activations. 
                 * A period of 0 results in a one-shot timer.
                 */
                jbpmTimerInstance.setPeriod(period);
                jbpmTimerInstance.setProcessInstanceId(pInstanceId);

                // timerTriggered string constant is required to trigger a timer as per TimerNodeInstance.signalEvent(....)
                return this.signalEvent( TIMER_TRIGGERED, jbpmTimerInstance, pInstanceId, sessionId);
            }else if (QuartzSchedulerService.ACTIVATION_TIMER_JOB.equals(timerType)){
                String processId = details[1];
                // in ProcessRuntimeImpl.startProcessInstance(..), the actionQueue appears to be empty after the initial timer invocation
                // so, can continue to execute startProcessAndReturnId(...) with the cron trigger 
                Map<String, Object> returnMap = this.startProcessAndReturnId(processId, null);
                return (Integer)returnMap.get(IKnowledgeSession.PROCESS_INSTANCE_STATE);
            }else {
                log.error("processJobExecution() TO-DO :  need to figure out how to implement behavior associated with timer type = "+timerType);
                return ProcessInstance.STATE_PENDING;
            }
        } catch (Exception x) {
            throw new RuntimeException(x);
        }
    }

    class KnowledgeSessionWrapper {

        StatefulKnowledgeSession ksession;
        KnowledgeRuntimeLogger rLogger;
        BAMProducerWrapper pWrapper;

        public KnowledgeSessionWrapper(StatefulKnowledgeSession x, AsyncBAMProducer bamProducer) {
            ksession = x;
            try {
                if(bamProducer != null){
                    pWrapper =  bamProducerPool.borrowObject();
                    bamProducer.setBAMProducerWrapper(pWrapper);
                }
            }catch(Exception e) {
                throw new RuntimeException(e);
            }
        }

        public void dispose() throws Exception {
            if(pWrapper != null)
                bamProducerPool.returnObject(pWrapper);

            if(rLogger != null) {
                rLogger.close();
            }
            ksession.execute(new CMTDisposeCommand());
        }

        public void setKnowledgeRuntimeLogger(KnowledgeRuntimeLogger x) {
            rLogger = x;
        }
    }

    public String getCurrentTimerJobsAsJson(String jobGroup) {
        try {
            return QuartzSchedulerService.getCurrentTimerJobsAsJson(jobGroup);
        }catch(Exception x){
            throw new RuntimeException(x);
        }
    }

    @Override
    public int purgeCurrentTimerJobs(String jobGroup) {
        try {
            return QuartzSchedulerService.purgeCurrentTimerJobs(jobGroup);
        }catch(Exception x){
            throw new RuntimeException(x);
        }
    }

}
