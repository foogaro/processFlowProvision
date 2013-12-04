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

package org.jboss.processFlow.tasks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Remote;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceUnit;
import javax.persistence.Query;
import javax.transaction.UserTransaction;
import javax.transaction.TransactionManager;
import javax.transaction.Transaction;

import org.apache.log4j.Logger;
import org.drools.SystemEventListener;
import org.drools.SystemEventListenerFactory;
import org.jbpm.task.*;
import org.jbpm.task.service.UserGroupCallbackManager;
import org.jbpm.task.event.TaskEventListener;
import org.jbpm.task.query.TaskSummary;
import org.jbpm.task.service.CannotAddTaskException;
import org.jbpm.task.service.ContentData;
import org.jbpm.task.service.EscalatedDeadlineHandler;
import org.jbpm.task.service.FaultData;
import org.jbpm.task.service.Operation;
import org.jbpm.task.service.PermissionDeniedException;
import org.jbpm.task.service.TaskException;
import org.jbpm.task.service.TaskService;
import org.jbpm.task.service.TaskServiceSession;

import org.jboss.processFlow.knowledgeService.IBaseKnowledgeSession;
import org.jboss.processFlow.PFPBaseService;
import org.jboss.processFlow.tasks.event.PfpTaskEventSupport;

/**  
 *  JA Bride
 *  23 March 2011
 *  Purpose :  synchronous API to various human task functions
 */
@Remote(ITaskService.class)
@Singleton(name="taskProxy")
@Startup
@Lock(LockType.READ)
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class HumanTaskService extends PFPBaseService implements ITaskService {

    public static final String JBPM_TASK_EMF_RESOURCE_LOCAL = "org.jbpm.task.resourceLocal";
    public static final String JBPM_TASK_EMF = "org.jbpm.task";
    public static final String TASK_BY_TASK_ID = "TaskByTaskId";
    public static final String GROUP="group";
    public static final String USER="user";

    private static final Logger log = Logger.getLogger("HumanTaskService");

    private boolean enableIntelligentMapping = false;

    private @PersistenceUnit(unitName=JBPM_TASK_EMF_RESOURCE_LOCAL) EntityManagerFactory humanTaskEMF;
    private @PersistenceUnit(unitName=JBPM_TASK_EMF) EntityManagerFactory jtaHumanTaskEMF;
    private @Resource UserTransaction uTrnx;
    private @Resource(name="java:/TransactionManager") TransactionManager tMgr;

    // https://issues.jboss.org/browse/AS7-4567
    //@EJB(name="kSessionProxy", beanName="ejb/prodKSessionProxy")
    @EJB(name="kSessionProxy", lookup=IBaseKnowledgeSession.BASE_JNDI)
    private IBaseKnowledgeSession kSessionProxy;

    private PfpTaskEventSupport eventSupport;
    private TaskService taskService;
    private Set<String> tempOrgEntitySet = new HashSet<String>();
    private boolean enableLog = true;
    
    
    // brms5.3.1 bombs and does not recover from concurrent clients attempting to add tasks with same OrganizationalEntity objects at the same time
    // this functionality prevents the following error from occuring:  
    //        ERROR: duplicate key value violates unique constraint "organizationalentity_pkey"
    // by:  org.jbpm.task.service.TaskServiceSession.addUserFromCallbackOperation(TaskServiceSession.java:1512) [jbpm-human-task-5.3.1.BRMS.jar:5.3.1.BRMS]
    private void checkAndSetTempOrgEntitySet(OrganizationalEntity orgObj) {
        String id = orgObj.getId();
        if(tempOrgEntitySet.contains(id))
            return;
        synchronized(tempOrgEntitySet){
            if(!tempOrgEntitySet.contains(id)){
                TaskServiceSession taskSession = null;
                try {
                    taskSession = taskService.createSession();
                    if(orgObj instanceof User)
                        taskSession.addUser((User)orgObj);
                    else
                        taskSession.addGroup((Group)orgObj);
                }catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    if(taskSession != null)
                        taskSession.dispose();
                }
                log.info("checkAndSetTempOrgEntitySet() adding following userId to tempOrgEntitySet to avoid possible duplicate entry complaints:  "+id);
                try{Thread.sleep(1000);}catch(Exception x){x.printStackTrace();}
                tempOrgEntitySet.add(id);
            }
        }
    }
    private void checkAndSetTempOrgEntitySet(String id, String type) {
        if(!tempOrgEntitySet.contains(id)){
            OrganizationalEntity orgObj = null;
            if(GROUP.equals(type)){
                orgObj = new Group(id);
            }else {
                orgObj = new User(id);
            }
            checkAndSetTempOrgEntitySet(orgObj);
        }
    }
    private void checkAndSetListOfOrgEntities(List<String> ids, String type){
        for(String id : ids){
            checkAndSetTempOrgEntitySet(id, type);
        }
    }
    private void checkAndSetListOfOrgEntities(List<OrganizationalEntity> orgObjs){
        for(OrganizationalEntity orgObj : orgObjs){
            checkAndSetTempOrgEntitySet(orgObj);
        }
    }


    /**
        - creates task with status of Ready

        this method could throw the following RuntimeExceptions :
          1)  org.hibernate.exception.ConstraintViolationException
            -- can occur if Potential Owner of task is assigned a userId or groupId that has not been previously loaded into the organizationalentity table
            -- note that apparently a task will still be persisted in database, however its status will be 'Created' and not 'Ready'
     */ 
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public long addTask(Task taskObj, ContentData cData) throws CannotAddTaskException {
        OrganizationalEntity orgOBj = taskObj.getTaskData().getCreatedBy();
        checkAndSetTempOrgEntitySet(orgOBj);
        checkAndSetListOfOrgEntities(taskObj.getPeopleAssignments().getPotentialOwners());
   
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            taskSession.addTask(taskObj, cData);
            int sessionId = taskObj.getTaskData().getProcessSessionId();
            eventSupport.fireTaskAdded(taskObj, cData);
            return taskObj.getId();
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }
    
    /*
        - changes task status from : Ready --> Reserved
        - TaskServiceSession set to 'local-JTA'
        - will set container managed trnx demarcation to NOT_SUPPORTED so as to leverage bean-managed trnx demarcation in TaskServiceSession
        - using BMT in TaskServiceSession will force a synchroneous trnx commit (and subsequent database flush)
    
     *    - http://en.wikibooks.org/wiki/Java_Persistence/Locking#Not_sending_version_to_client.2C_only_locking_on_the_server
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void claimTask(Long taskId, String idRef, String userId, List<String> roles) throws TaskException {
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            taskSession.taskOperation(Operation.Claim, taskId, userId, null, null, roles);
            Task taskObj = taskSession.getTask(taskId);
            int sessionId = taskObj.getTaskData().getProcessSessionId();
            eventSupport.fireTaskClaimed(taskId, userId);
        } catch(javax.persistence.RollbackException x) {
            Throwable firstCause = x.getCause();
            Throwable secondCause = null;
            if(firstCause != null) {
                secondCause = firstCause.getCause();
            }
            StringBuilder sBuilder = new StringBuilder();
            sBuilder.append("claimTask() 1. exception = : "+x);
            sBuilder.append(taskId);
            sBuilder.append("\n\tcause(s) = "+x.getCause()+"\n\t"+secondCause);
            log.error(sBuilder.toString());
            throw new PermissionDeniedException(sBuilder.toString());
        }catch(RuntimeException x) {
            Throwable firstCause = x.getCause();
            Throwable secondCause = null;
            if(firstCause != null) {
                secondCause = firstCause.getCause();
            }
            StringBuilder sBuilder = new StringBuilder();
            sBuilder.append("claimTask() 2. exception = : "+x);
            sBuilder.append(taskId);
            sBuilder.append("\n\tcause(s) = "+x.getCause()+"\n\t"+secondCause);
            log.error(sBuilder.toString());
            throw new PermissionDeniedException(sBuilder.toString());
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }
    
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public TaskSummary guaranteedClaimTaskAssignedAsPotentialOwnerByStatusByGroup(String userId, List<String> groupIds, List<Status> statuses, String language, Integer firstResult, Integer maxResults){
        List<TaskSummary> taskList = this.getTasksAssignedAsPotentialOwnerByStatusByGroup(userId, groupIds, statuses, language, firstResult, maxResults);
        TaskSummary claimedTask = null;
        for(TaskSummary tObj : taskList){
            try {
                this.claimTask(tObj.getId(), userId, userId, groupIds);
                claimedTask = tObj;
                break;
            }catch(org.jbpm.task.service.PermissionDeniedException x){
                if(enableLog){
                    log.error("guaranteedClaimTaskAssignedAsPotentialOwnerByStatusByGroup() PermissionDeniedException for taskId = "+tObj.getId());
                }
            }
        }
        return claimedTask;
    }

    /**
        - changes task status from : InProgress --> Completed
        - this method blocks until process instance arrives at next 'safe point'
        - this method uses BMT to define the scope of the transaction    , boolean disposeKsession)
     */
    public void completeTask(Long taskId, Map<String, Object> outboundTaskVars, String userId)  {
        TaskServiceSession taskSession = null;
        try {
            taskSession = jtaTaskService.createSession();
            Task taskObj = taskSession.getTask(taskId);
            int sessionId = taskObj.getTaskData().getProcessSessionId();
            long pInstanceId = taskObj.getTaskData().getProcessInstanceId();
            if(taskObj.getTaskData().getStatus() != Status.InProgress) {
                log.warn("completeTask() task with following id will be changed to status of InProgress: "+taskId);
                taskSession.taskOperation(Operation.Start, taskId, userId, null, null, null);
                eventSupport.fireTaskStarted(taskId, userId);
            }
   
            if(outboundTaskVars == null)
                outboundTaskVars = new HashMap<String, Object>(); 

            //~~ Nick: intelligent mapping the task input parameters as the results map
            // that said, copy the input parameters to the result map
            Map<String, Object> newOutboundTaskVarMap = null;
            if (isEnableIntelligentMapping()) {
                long documentContentId = taskObj.getTaskData().getDocumentContentId();
                if(documentContentId == -1)
                    throw new RuntimeException("completeTask() documentContent must be created with addTask invocation");
    
                // 1) constructure a purely empty new HashMap, as the final results map, which will be mapped out to the process instance
                newOutboundTaskVarMap = new HashMap<String, Object>();
                // 2) put the original input parameters first
                newOutboundTaskVarMap.putAll(populateHashWithTaskContent(taskSession.getContent(documentContentId), "documentContent"));
                // 3) put the user modified into the final result map, replace the original values with the user modified ones if any
                newOutboundTaskVarMap.putAll(outboundTaskVars);
            }   //~~  intelligent mapping
            else {
                newOutboundTaskVarMap = outboundTaskVars;
            }

            ContentData contentData = this.convertTaskVarsToContentData(newOutboundTaskVarMap, null);
            taskSession.taskOperation(Operation.Complete, taskId, userId, null, contentData, null);
            eventSupport.fireTaskCompleted(taskId, userId);

            StringBuilder sBuilder = new StringBuilder("completeTask()");
            this.dumpTaskDetails(taskObj, sBuilder);
            
            // add TaskChangeDetails to outbound variables so that downstream branches can route accordingly
            TaskChangeDetails changeDetails = new TaskChangeDetails();
            changeDetails.setNewStatus(Status.Completed);
            changeDetails.setReason(TaskChangeDetails.NORMAL_COMPLETION_REASON);
            changeDetails.setTaskId(taskId);
            newOutboundTaskVarMap.put(TaskChangeDetails.TASK_CHANGE_DETAILS, changeDetails);
   
            kSessionProxy.completeWorkItem(taskObj.getTaskData().getWorkItemId(), newOutboundTaskVarMap, pInstanceId, sessionId);
        }catch(org.jbpm.task.service.PermissionDeniedException x) {
            throw x;
        }catch(RuntimeException x) {
            if(x.getCause() != null && (x.getCause() instanceof javax.transaction.RollbackException) && (x.getMessage().indexOf("Could not commit transaction") != -1)) {
                String message = "completeTask() RollbackException thrown most likely because the following task object is dirty : "+taskId;
                log.error(message);
                throw new PermissionDeniedException(message);
            }else {
                throw x;
            }
        }catch(Exception x) {
            throw new RuntimeException(x);
        }
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void delegateTask(Long taskId, String userId, String targetUserId) {
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            taskSession.taskOperation(Operation.Delegate, taskId, userId, targetUserId, null, null);
            //eventSupport.fireTaskDelegated(taskId, userId, targetUserId);
        }catch(RuntimeException x) {
            throw x;
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }
    
    /**
     * NOTE:  allows for a null userId ... in which case this implementation will use the actual owner of the task to execute the fail task operation
     */
    public void failTask(Long taskId, Map<String, Object> outboundTaskVars, String userId, String faultName) {
        TaskServiceSession taskSession = null;
        try {
            taskSession = jtaTaskService.createSession();
    
            Task taskObj = taskSession.getTask(taskId);
            int sessionId = taskObj.getTaskData().getProcessSessionId();
            long pInstanceId = taskObj.getTaskData().getProcessInstanceId();
            if(taskObj.getTaskData().getStatus() != Status.InProgress) {
                throw new PermissionDeniedException("failTask() will not attempt operation due to incorrect existing status of : "+taskObj.getTaskData().getStatus());
            }
            if(userId == null){
                userId = taskObj.getTaskData().getActualOwner().getId();
            }
            FaultData contentData = (FaultData)this.convertTaskVarsToContentData(outboundTaskVars, faultName);
            taskSession.taskOperation(Operation.Fail, taskId, userId, null, contentData, null);
            eventSupport.fireTaskFailed(taskId, userId);

            StringBuilder sBuilder = new StringBuilder("failTask()");
            this.dumpTaskDetails(taskObj, sBuilder);
           
            kSessionProxy.completeWorkItem(taskObj.getTaskData().getWorkItemId(), outboundTaskVars, pInstanceId, sessionId);
        }catch(PermissionDeniedException x) {
            throw new RuntimeException(x);
        }
    }
    
    public void nominateTask(final long taskId, String userId, final List<OrganizationalEntity> potentialOwners){
        throw new RuntimeException("nominateTask()  PLEASE IMPLEMENT ME");
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void releaseTask(Long taskId, String userId){
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            Task taskObj = taskSession.getTask(taskId);
            int sessionId = taskObj.getTaskData().getProcessSessionId();
            taskSession.taskOperation(Operation.Release, taskId, userId, null, null, null);
            eventSupport.fireTaskReleased(taskId, userId);
        } catch(Exception x) {
            throw new RuntimeException(x);
        } finally {
            if (taskSession != null)
                taskSession.dispose();
        }
    }
    
    /**
     * skipTask
     * <pre>
     * NOTE:  underlying jbpm5 TaskServiceSession does not allow for outbound task variables with Operation.Skip
     * </pre>
     */
    public void skipTask(Long taskId, String userId, Map<String, Object> outboundTaskVars) {
        TaskServiceSession taskSession = null;
        try {
            taskSession = jtaTaskService.createSession();
    
            Task taskObj = taskSession.getTask(taskId);
            int sessionId = taskObj.getTaskData().getProcessSessionId();
            long pInstanceId = taskObj.getTaskData().getProcessInstanceId();
            taskSession.taskOperation(Operation.Skip, taskId, userId, null, null, null);
            eventSupport.fireTaskSkipped(taskId, userId);

            StringBuilder sBuilder = new StringBuilder("skipTask()");
            this.dumpTaskDetails(taskObj, sBuilder);
           
            kSessionProxy.completeWorkItem(taskObj.getTaskData().getWorkItemId(), outboundTaskVars, pInstanceId, sessionId);
        }catch(org.jbpm.task.service.PermissionDeniedException x) {
            throw x;
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void skipTaskByWorkItemId(Long workItemId){
        TaskServiceSession taskSession = null;
        try {
            Task taskObj = getTaskByWorkItemId(workItemId);
            Status tStatus = taskObj.getTaskData().getStatus();
            int sessionId = taskObj.getTaskData().getProcessSessionId();
            if(tStatus == Status.Obsolete || tStatus == Status.Error || tStatus == Status.Failed) {
                log.error("skipTaskByWorkItemId() can not skip task since status is : "+tStatus.name());
                return;
            }
            taskSession = taskService.createSession();

            String userId = ITaskService.ADMINISTRATOR;
            User actualOwner = taskObj.getTaskData().getActualOwner();
            if(actualOwner != null)
                userId = actualOwner.getId();
            taskSession.taskOperation(Operation.Skip, taskObj.getId(), userId, null, null, null);
            eventSupport.fireTaskSkipped(taskObj.getId(), userId);
        }catch(RuntimeException x) {
            throw x;
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    /**
        - changes task status from : Reserved --> InProgress
        - TaskServiceSession set to 'local-JTA'
        - will set container managed trnx demarcation to NOT_SUPPORTED(NEVER) so as to leverage bean-managed trnx demarcation in TaskServiceSession
        - using BMT in TaskServiceSession will force a synchroneous trnx commit (and subsequent database flush)
     */
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void startTask(Long taskId, String userId) {
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            Task taskObj = taskSession.getTask(taskId);
            int sessionId = taskObj.getTaskData().getProcessSessionId();
    
            taskSession.taskOperation(Operation.Start, taskId, userId, null, null, null);
            eventSupport.fireTaskStarted(taskId, userId);
        }catch(Exception x) {
            throw new RuntimeException("startTask", x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    /*
     *  NOTE:  use with caution
     *      in particular, this implementation uses a JTA enabled entity manager factory 
     *      the reason is that in postgresql, using a RESOURCE_LOCAL EMF throws a "Large Object exception" (google it)
     *      so try to avoid taxing the transaction manager with an abusive amount of calls to this function
     */
    public TaskSummary getTask(Long taskId){
        TaskServiceSession taskSession = null;
        try {
            taskSession = jtaTaskService.createSession();
            Task taskObj = taskSession.getTask(taskId);
            TaskSummary tSummary = new TaskSummary();
            tSummary.setActivationTime(taskObj.getTaskData().getExpirationTime());
            tSummary.setActualOwner(taskObj.getTaskData().getActualOwner());
            tSummary.setCreatedBy(taskObj.getTaskData().getCreatedBy());
            tSummary.setCreatedOn(taskObj.getTaskData().getCreatedOn());
            tSummary.setDescription(taskObj.getDescriptions().get(0).getText());
            tSummary.setExpirationTime(taskObj.getTaskData().getExpirationTime());
            tSummary.setId(taskObj.getId());
            tSummary.setName(taskObj.getNames().get(0).getText());
            tSummary.setPriority(taskObj.getPriority());
            tSummary.setProcessId(taskObj.getTaskData().getProcessId());
            tSummary.setProcessInstanceId(taskObj.getTaskData().getProcessInstanceId());
            tSummary.setStatus(taskObj.getTaskData().getStatus());
            tSummary.setSubject(taskObj.getSubjects().get(0).getText());
            return tSummary;
        }catch(RuntimeException x) {
            throw x;
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }
    
    public Task getTaskByWorkItemId(Long workItemId){
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            return taskSession.getTaskByWorkItemId(workItemId);
        }catch(RuntimeException x) {
            throw x;
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<TaskSummary> getTasksAssignedAsPotentialOwner(String userId, List<String> groupIds, String language) {
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            return taskSession.getTasksAssignedAsPotentialOwner(userId, groupIds, language);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }
    
    
    public void dumpTaskDetails(Task taskObj, StringBuilder sBuilder) {
        long workItemId = taskObj.getTaskData().getWorkItemId();
        int ksessionIdFromTask = taskObj.getTaskData().getProcessSessionId(); 
        long documentContentId = taskObj.getTaskData().getDocumentContentId();
        long outputContentId = taskObj.getTaskData().getOutputContentId();
        long processInstanceId = taskObj.getTaskData().getProcessInstanceId();
    
        sBuilder.append("\n\ttaskId = ");
        sBuilder.append(taskObj.getId());
        sBuilder.append("\n\tworkItemId = ");
        sBuilder.append(workItemId);
        sBuilder.append("\n\tvariables:  processInstance --> task :  documentContentId = ");
        sBuilder.append(documentContentId);
        sBuilder.append("\n\tvariables:  task --> processInstance :  outputContentId = ");
        sBuilder.append(outputContentId);
        sBuilder.append("\n\tprocessInstanceId = ");
        sBuilder.append(processInstanceId);
        sBuilder.append("\n\tksessionIdFromTask = ");
        sBuilder.append(ksessionIdFromTask);
        log.info(sBuilder.toString());
    }

    /*
     *  NOTE:  use with caution
     *      in particular, this implementation uses a JTA enabled entity manager factory 
     *      the reason is that in postgresql, using a RESOURCE_LOCAL EMF throws a "Large Object exception" (google it)
     *      so try to avoid taxing the transaction manager with an abusive amount of calls to this function
     */
    public String getTaskName(Long taskId, String language) {
        TaskServiceSession taskSession  = null;
        try {
            taskSession = jtaTaskService.createSession();
            Task taskObj = taskSession.getTask(taskId);
            
            Iterator iTasks = taskObj.getNames().iterator();
            while(iTasks.hasNext()){
                I18NText iObj = (I18NText)iTasks.next();
                if(iObj.getLanguage().equals(language))
                    return iObj.getText();
            }
            throw new RuntimeException("getTaskName() can not find taskName for taskId = "+taskId+" : language = "+language);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public Map<String,Object> getTaskContent(Long taskId, Boolean inbound) {
        StringBuilder qBuilder = new StringBuilder();
        qBuilder.append("select c.content from Content c, Task t where c.id = ");
        if(inbound)
            qBuilder.append("t.taskData.documentContentId ");
        else
            qBuilder.append("t.taskData.outputContentId ");
        qBuilder.append("and t.id = ");
        qBuilder.append(taskId);
        EntityManager eManager = null;
        ObjectInputStream in = null;
        try {
            eManager = humanTaskEMF.createEntityManager();
            uTrnx.begin();
            Query queryObj = eManager.createQuery(qBuilder.toString());
            byte[] contentBytes = (byte[])queryObj.getSingleResult();
            uTrnx.commit();
            log.info("getTaskContent() taskId = "+taskId+" : inbound = "+inbound+" contentBytes size = "+contentBytes.length);
            ByteArrayInputStream bis = new ByteArrayInputStream(contentBytes);
            in = new ObjectInputStream(bis);
            return (Map<String,Object>)in.readObject();
        } catch(Exception x) {
            throw new RuntimeException(x);
        } finally {
            if(eManager != null)
                eManager.close();
            try {
                if(in != null)
                    in.close();
            }catch(Exception x){x.printStackTrace();}
        }
    }

    //  should consider using taskServiceSession.setOutput(final long taskId, final String userId, final ContentData outputContentData) directly
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void setTaskContent(Task taskObj, Boolean inbound, Map<String, Object> taskContent) {
        EntityManager eManager = null;
        ObjectOutputStream out = null; 
        try {
            eManager = humanTaskEMF.createEntityManager();
            uTrnx.begin();
            
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out = new ObjectOutputStream(bos);
            out.writeObject(taskContent);
            out.close();
            byte[] byteResults = bos.toByteArray();
            
         // persist the serialized results map into Content table
            Content content = new Content();
            content.setContent(byteResults);
            eManager.persist(content); // will generate a unique id for this content by jpa

            ContentData contentData = new ContentData();
            contentData.setContent(byteResults);
            contentData.setAccessType(org.jbpm.task.AccessType.Inline);
            taskObj.getTaskData().setOutput(content.getId(), contentData);
            eManager.merge(taskObj);
            
            uTrnx.commit();
            log.info("setTaskContent() taskId = "+taskObj.getId()+" : inbound = "+inbound+" contentBytes size = "+byteResults.length);
        } catch(Exception x) {
            throw new RuntimeException(x);
        } finally {
            if(eManager != null)
                eManager.close();
            try {
                if(out != null)
                    out.close();
            }catch(Exception x){x.printStackTrace();}
        }
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public String printTaskContent(Long taskId, Boolean inbound) {
        Map<?,?> contentHash = getTaskContent(taskId, inbound);
        StringBuilder sBuilder = new StringBuilder("taskContent taskId = ");
        sBuilder.append(taskId);
        sBuilder.append("\t: inbound = ");
        sBuilder.append(inbound);
        for (Map.Entry<?, ?> entry: contentHash.entrySet()) {
            sBuilder.append("\n\tkey ="+entry.getKey()+"\t: value = "+entry.getValue());
        }
        return sBuilder.toString();
    }

    // NOTE:  implementation delegates transaction management to BMT capabilities of jbpm5 TaskServiceSession
    // JA Bride:  15 May 2012:  looks like taskServiceSession.query(....) no longer manages its own trnxs ... will do so now
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<TaskSummary> getTasksByProcessInstance(Long processInstanceId, Status taskStatus) {
        TaskServiceSession taskSession = null;
        try {
            StringBuilder qBuilder = new StringBuilder("select new org.jbpm.task.query.TaskSummary(t.id, t.taskData.processInstanceId, name.text, subject.text, description.text, t.taskData.status, t.priority, t.taskData.skipable, t.taskData.actualOwner, t.taskData.createdBy, t.taskData.createdOn, t.taskData.activationTime, t.taskData.expirationTime, t.taskData.processId, t.taskData.processSessionId) ");
            qBuilder.append("from Task t ");
            qBuilder.append("left join t.taskData.createdBy ");
            qBuilder.append("left join t.taskData.actualOwner ");
            qBuilder.append("left join t.subjects as subject ");
            qBuilder.append("left join t.descriptions as description ");
            qBuilder.append("left join t.names as name ");
            qBuilder.append("where t.taskData.processInstanceId = ");
            qBuilder.append(processInstanceId);
            if(taskStatus != null) {
                qBuilder.append(" and t.taskData.status = '");
                qBuilder.append(taskStatus.name());
                qBuilder.append("'");
            }
            taskSession = taskService.createSession();
            uTrnx.begin();
            List<TaskSummary> taskSummaryList = (List<TaskSummary>)taskSession.query(qBuilder.toString(), 10, 0);
            uTrnx.commit();
            return taskSummaryList;
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<TaskSummary> getTasksAssignedAsPotentialOwner(String userId, List<String> groupIds, String language, Integer firstResult, Integer maxResults) {
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            return taskSession.getTasksAssignedAsPotentialOwner(userId, groupIds, language, firstResult, maxResults);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }
    
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<TaskSummary> getTasksAssignedAsPotentialOwnerByStatusByGroup(String userId, List<String> groupIds, List<Status> statuses, String language, Integer firstResult, Integer maxResults){
        this.checkAndSetListOfOrgEntities(groupIds, GROUP);
        this.checkAndSetTempOrgEntitySet(userId, USER);
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            return taskSession.getTasksAssignedAsPotentialOwnerByStatusByGroup(userId, groupIds, statuses, language);
            
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<TaskSummary> getTasksAssignedAsPotentialOwnerByStatus(String userId, List<Status> statuses, String language, Integer firstResult, Integer maxResults){
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            return taskSession.getTasksAssignedAsPotentialOwnerByStatus(userId, statuses, language);
            
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<TaskSummary> getAssignedTasks(String userId, List<Status> statuses, String language) {
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            //return taskSession.getTasksOwned(userId, statuses, language);
            return taskSession.getTasksOwned(userId, language);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }     
    }
    
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public List<?> query(String qlString, Integer size, Integer offset) {
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            return taskSession.query(qlString, size, offset);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    @PostConstruct
    public void start() throws Exception {
        // 0)  set properties for UserGroupCallbackManager
        UserGroupCallbackManager callbackMgr = UserGroupCallbackManager.getInstance();
        callbackMgr.setCallback(new org.jboss.processFlow.tasks.identity.PFPUserGroupCallback());        
        callbackMgr.setProperty("disable.all.groups", "true");
        log.info("start() just set UserGroupCallbackManager with properties");

        // 1)  instantiate a deadline handler 
        EscalatedDeadlineHandler deadlineHandler = null;
        if (System.getProperty(ITaskService.DEADLINE_HANDLER) != null) {
            deadlineHandler = (EscalatedDeadlineHandler) Class.forName(System.getProperty(ITaskService.DEADLINE_HANDLER)).newInstance();
        } else {
            throw new RuntimeException("start() need to pass system property = "+ITaskService.DEADLINE_HANDLER);
        }
        
        SystemEventListener sEventListener = SystemEventListenerFactory.getSystemEventListener();
        
        // 2) turn on/off the intelligent human task input/output mapping
        if (System.getProperty("org.jboss.processFlow.task.enableIntelligentMapping") != null)
            enableIntelligentMapping = Boolean.parseBoolean(System.getProperty("org.jboss.processFlow.task.enableIntelligentMapping"));
        log.info("start() enableIntelligentMapping = " + enableIntelligentMapping);
        
        
        // 3) instantiate TaskEventListeners
        log.info("TaskEventListeners: " + System.getProperty("org.jboss.processFlow.tasks.TaskEventListeners"));
        eventSupport = new PfpTaskEventSupport();
        if (System.getProperty("org.jboss.processFlow.tasks.TaskEventListeners") != null) {
            String[] listenerClasses = System.getProperty("org.jboss.processFlow.tasks.TaskEventListeners").split("\\s");
            for (String lcn : listenerClasses) {
                eventSupport.addEventListener((TaskEventListener) Class.forName(lcn).newInstance());
            }
        }

        EntityManager eManager = null;
        try {
            eManager = humanTaskEMF.createEntityManager();
            Query queryObj = eManager.createQuery("from User");
            List<User> userList = queryObj.getResultList();
            for(User userObj : userList){
                this.tempOrgEntitySet.add(userObj.getId());
            }
            log.info("start() just populated tempOrgEntitySet with following # of orgentity users found in DB: "+userList.size());
        }finally {
        }
        
        jtaTaskService  = new TaskService(jtaHumanTaskEMF, sEventListener, deadlineHandler);
        
        /**   1)  since TaskService is using a RESOURCE-LOCAL EMF (for performance reasons), jbpm assumes it can define its own trnx boundaries
         *    2)  in particular, jbpm human task impl defines its own trnx boundaries when this function creates a jbpm TaskService
         *    3)  not sure how to disable a JTA trnx in this @PostConstruct function other than to suspend the trnx using the TransactionManager 
         */
        try {
            Transaction suspendedTrnx = tMgr.suspend(); 
            taskService = new TaskService(humanTaskEMF, sEventListener, deadlineHandler);
            tMgr.resume(suspendedTrnx);
        } catch(Exception x) {
            throw new RuntimeException(x);
        }
        
        
    }

    @PreDestroy
    public void stop() throws Exception {
        log.info("stop()");
    }

    /*
     * if faultName != null, then returns a FaultData object with its faultName property set
     * otherwise, returns a ContentData object
     */
    private ContentData convertTaskVarsToContentData(Map<String, Object> taskVars, String faultName) {
        ContentData contentData = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out;
        try {
            if(faultName != null){
                contentData = new FaultData();
                ((FaultData)contentData).setFaultName(faultName);
            }else {
                contentData = new ContentData();
            }
            out = new ObjectOutputStream(bos);
            out.writeObject(taskVars);
            contentData.setContent(bos.toByteArray());
            contentData.setAccessType(org.jbpm.task.AccessType.Inline);
            out.close();
            return contentData;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void rollbackTrnx() {
        try {
            if(uTrnx.getStatus() == javax.transaction.Status.STATUS_ACTIVE)
                uTrnx.rollback();
        }catch(Exception e) {
            e.printStackTrace();
        }
    }


    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public Content getContent(Long contentId){
        TaskServiceSession taskSession = null;
        try {
            taskSession = taskService.createSession();
            return taskSession.getContent(contentId);
        }catch(Exception x) {
            throw new RuntimeException(x);
        }finally {
            if(taskSession != null)
                taskSession.dispose();
        }
    }

    /*  populateHashWithTaskContent
            - query database for Content object and stream back as a Map
            - Long contentid    :   contentId to be retrieved from database
            - String keyName    :   name of key in returned Map whose value should be the raw content Object
     */
    public Map populateHashWithTaskContent(Content contentObj, String keyName) {
        Map<String, Object> returnHash = null;
        long contentId = contentObj.getId();
        if(contentObj != null) {
            returnHash = new HashMap<String, Object>();
            ByteArrayInputStream bis = new ByteArrayInputStream(contentObj.getContent());
            ObjectInputStream in;
            try {
                in = new ObjectInputStream(bis);
                Map<?, ?> contentHash = (Map<?,?>)in.readObject();
                in.close();
                if(contentHash != null && contentHash.size() > 0) {
                    for (Map.Entry<?, ?> entry: contentHash.entrySet()) {
                        if (entry.getKey() instanceof String) {
                            returnHash.put((String) entry.getKey(), entry.getValue());
                        } else {
                             log.warn("populateHashWithTaskContent() content with id ="+contentId+ " includes non-string variable : "+entry.getKey()+" : "+entry.getValue());
                        }
                    }
                } else {
                    log.warn("populateHashWithTaskContent() no content variables found for contentId = "+contentId);
                }
                returnHash.put(keyName, contentHash);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            log.error("populateHashWithTaskContent() returned null content for contentId = "+contentId);
        }
        return returnHash;
    }

    /**
     * @return the enableIntelligentMapping
     */
    public boolean isEnableIntelligentMapping() {
        return enableIntelligentMapping;
    }

    /**
     * Expose this method, so that the user can change the setting at runtime
     * 
     * @param enableIntelligentMapping the enableIntelligentMapping to set
     */
    public void setEnableIntelligentMapping(boolean enableIntelligentMapping) {
        this.enableIntelligentMapping = enableIntelligentMapping;
    }
 
}
