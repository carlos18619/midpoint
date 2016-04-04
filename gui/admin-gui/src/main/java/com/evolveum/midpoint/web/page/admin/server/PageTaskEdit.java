/*
 * Copyright (c) 2010-2016 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.web.page.admin.server;

import com.evolveum.midpoint.gui.api.model.LoadableModel;
import com.evolveum.midpoint.model.api.ModelPublicConstants;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskCategory;
import com.evolveum.midpoint.task.api.TaskManager;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SystemException;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.web.application.AuthorizationAction;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.web.component.prism.ContainerStatus;
import com.evolveum.midpoint.web.component.prism.ObjectWrapper;
import com.evolveum.midpoint.web.component.prism.ObjectWrapperFactory;
import com.evolveum.midpoint.web.component.refresh.AutoRefreshDto;
import com.evolveum.midpoint.web.component.refresh.AutoRefreshPanel;
import com.evolveum.midpoint.web.component.refresh.Refreshable;
import com.evolveum.midpoint.web.page.admin.PageAdmin;
import com.evolveum.midpoint.web.page.admin.server.dto.TaskDto;
import com.evolveum.midpoint.web.page.admin.server.dto.TaskDtoExecutionStatus;
import com.evolveum.midpoint.web.page.admin.server.dto.TaskDtoProviderOptions;
import com.evolveum.midpoint.web.util.OnePageParameterEncoder;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.WfContextType;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AbstractAjaxTimerBehavior;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.util.time.Duration;

import java.util.Collection;
import java.util.Iterator;

/**
 * @author mederly
 */
@PageDescriptor(url = "/admin/task2", encoder = OnePageParameterEncoder.class, action = {
		@AuthorizationAction(actionUri = PageAdminTasks.AUTHORIZATION_TASKS_ALL,
				label = PageAdminTasks.AUTH_TASKS_ALL_LABEL,
				description = PageAdminTasks.AUTH_TASKS_ALL_DESCRIPTION),
		@AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_TASK_URL,
				label = "PageTaskEdit.auth.task.label",
				description = "PageTaskEdit.auth.task.description")})

public class PageTaskEdit extends PageAdmin implements Refreshable {

	private static final int REFRESH_INTERVAL_IF_RUNNABLE = 2000;
	private static final int REFRESH_INTERVAL_IF_SUSPENDED = 10000;
	private static final int REFRESH_INTERVAL_IF_WAITING = 10000;
	private static final int REFRESH_INTERVAL_IF_CLOSED = 60000;

	private static final String DOT_CLASS = PageTaskEdit.class.getName() + ".";
	private static final String OPERATION_LOAD_TASK = DOT_CLASS + "loadTask";
	static final String OPERATION_SAVE_TASK = DOT_CLASS + "saveTask";

	public static final String ID_SUMMARY_PANEL = "summaryPanel";
	public static final String ID_MAIN_PANEL = "mainPanel";

	private static final Trace LOGGER = TraceManager.getTrace(PageTaskEdit.class);

	private String taskOid;
	private LoadableModel<TaskDto> taskDtoModel;
	private LoadableModel<ObjectWrapper<TaskType>> objectWrapperModel;
	private boolean edit = false;

	private PageTaskController controller = new PageTaskController(this);

	private TaskMainPanel mainPanel;
	private AbstractAjaxTimerBehavior refreshingBehavior;
	private IModel<AutoRefreshDto> refreshModel;

	public PageTaskEdit(PageParameters parameters) {

		getPageParameters().overwriteWith(parameters);
		taskOid = getPageParameters().get(OnePageParameterEncoder.PARAMETER).toString();

		final OperationResult result = new OperationResult(OPERATION_LOAD_TASK);
		final Task operationTask = getTaskManager().createTaskInstance(OPERATION_LOAD_TASK);
		final TaskType taskType = loadTaskTypeChecked(taskOid, operationTask, result);
		final TaskDto taskDto;
		try {
			taskDto = prepareTaskDto(taskType, operationTask, result);
		} catch (SchemaException|ObjectNotFoundException e) {
			throw new SystemException("Couldn't prepare task DTO: " + e.getMessage(), e);
		}
		taskDtoModel = new LoadableModel<TaskDto>() {
			@Override
			protected TaskDto load() {
				return taskDto;
			}
		};
		final ObjectWrapper<TaskType> wrapper = loadObjectWrapper(taskType.asPrismObject(), result);
		objectWrapperModel = new LoadableModel<ObjectWrapper<TaskType>>() {
			@Override
			protected ObjectWrapper<TaskType> load() {
				return wrapper;
			}
		};

		edit = false;
		initLayout();
	}

	private TaskType loadTaskTypeChecked(String taskOid, Task operationTask, OperationResult result) {
		TaskType taskType = loadTaskType(taskOid, operationTask, result);

		if (!result.isSuccess()) {
			showResult(result);
		}

		if (taskType == null) {
			getSession().error(getString("pageTaskEdit.message.cantTaskDetails"));
			showResult(result, false);
			throw getRestartResponseException(PageTasks.class);
		}

		return taskType;
	}

	TaskType loadTaskType(String taskOid, Task operationTask, OperationResult result) {
		TaskType taskType = null;

		try {
			Collection<SelectorOptions<GetOperationOptions>> options =
					GetOperationOptions.createRetrieveAttributesOptions(
							TaskType.F_SUBTASK, TaskType.F_NODE_AS_OBSERVED, TaskType.F_NEXT_RUN_START_TIMESTAMP);
			options.add(SelectorOptions.create(new ItemPath(TaskType.F_WORKFLOW_CONTEXT, WfContextType.F_WORK_ITEM), GetOperationOptions.createRetrieve()));
			taskType = getModelService().getObject(TaskType.class, taskOid, options, operationTask, result).asObjectable();
			result.computeStatus();
		} catch (Exception ex) {
			result.recordFatalError("Couldn't get task.", ex);
		}
		return taskType;
	}

	private TaskDto prepareTaskDto(TaskType task, Task operationTask, OperationResult result) throws SchemaException, ObjectNotFoundException {
		TaskDto taskDto = new TaskDto(task, getModelService(), getTaskService(), getModelInteractionService(),
				getTaskManager(), TaskDtoProviderOptions.fullOptions(), operationTask, result, this);
		return taskDto;
	}


	protected void initLayout() {

		refreshModel = new Model(new AutoRefreshDto());
		refreshModel.getObject().setInterval(getRefreshInterval());

		IModel<PrismObject<TaskType>> prismObjectModel = new AbstractReadOnlyModel<PrismObject<TaskType>>() {
			@Override
			public PrismObject<TaskType> getObject() {
				return objectWrapperModel.getObject().getObject();
			}
		};
		final TaskSummaryPanel summaryPanel = new TaskSummaryPanel(ID_SUMMARY_PANEL, prismObjectModel, refreshModel, this);
		summaryPanel.setOutputMarkupId(true);
		add(summaryPanel);

		mainPanel = new TaskMainPanel(ID_MAIN_PANEL, objectWrapperModel, taskDtoModel, this);
		mainPanel.setOutputMarkupId(true);
		add(mainPanel);

		createRefreshingBehavior();
		addRefreshingBehavior();
	}

	private void createRefreshingBehavior() {
		refreshingBehavior = new AbstractAjaxTimerBehavior(Duration.milliseconds(refreshModel.getObject().getInterval())) {
			@Override
			protected void onTimer(AjaxRequestTarget target) {
				AutoRefreshDto refreshDto = refreshModel.getObject();
//				if (refreshDto.shouldRefresh()) {
					refresh(target);
//				} else {
//					target.add(summaryPanel.getRefreshPanel());
//				}
			}
		};
	}

	private int getRefreshInterval() {
		TaskDtoExecutionStatus exec = getTaskDto().getExecution();
		switch (exec) {
			case RUNNABLE:
			case RUNNING:
			case RUNNING_OR_RUNNABLE:
			case SUSPENDING: return REFRESH_INTERVAL_IF_RUNNABLE;
			case SUSPENDED: return REFRESH_INTERVAL_IF_SUSPENDED;
			case WAITING: return REFRESH_INTERVAL_IF_WAITING;
			case CLOSED: return REFRESH_INTERVAL_IF_CLOSED;
		}
		return REFRESH_INTERVAL_IF_RUNNABLE;
	}

	public void refresh(AjaxRequestTarget target) {
		refreshTaskModels();
		Iterator<Component> componentIterator = mainPanel.getTabPanel().iterator();
		while (componentIterator.hasNext()) {
			Component component = componentIterator.next();
			if (component instanceof TaskTabPanel) {
				for (Component c : ((TaskTabPanel) component).getComponentsToUpdate()) {
					target.add(c);
				}
			}
		}
		target.add(getSummaryPanel());
		target.add(mainPanel.getButtonPanel());

		AutoRefreshDto refreshDto = refreshModel.getObject();
		refreshDto.recordRefreshed();

		if (refreshDto.isEnabled()) {
			int computedInterval = getRefreshInterval();
			if (computedInterval != refreshDto.getInterval()) {
				refreshDto.setInterval(computedInterval);
				if (getRefreshPanel().getBehaviors().contains(refreshingBehavior)) {
					stopRefreshing();
					removeRefreshingBehavior();
				}
				createRefreshingBehavior();
				addRefreshingBehavior();
			} else {
				refreshRefreshing();
			}
		}
	}

	public void refreshTaskModels() {
		TaskDto oldTaskDto = taskDtoModel.getObject();
		if (oldTaskDto == null) {
			LOGGER.warn("Null or empty taskModel");
			return;
		}
		TaskManager taskManager = getTaskManager();
		OperationResult result = new OperationResult("refresh");
		Task operationTask = taskManager.createTaskInstance("refresh");

		try {
			LOGGER.debug("Refreshing task {}", oldTaskDto);
			TaskType taskType = loadTaskType(oldTaskDto.getOid(), operationTask, result);
			TaskDto newTaskDto = prepareTaskDto(taskType, operationTask, result);
			final ObjectWrapper<TaskType> newWrapper = loadObjectWrapper(taskType.asPrismObject(), result);
			taskDtoModel.setObject(newTaskDto);
			objectWrapperModel.setObject(newWrapper);
		} catch (ObjectNotFoundException|SchemaException|RuntimeException e) {
			LoggingUtils.logUnexpectedException(LOGGER, "Couldn't refresh task {}", e, oldTaskDto);
		}
	}

	protected ObjectWrapper<TaskType> loadObjectWrapper(PrismObject<TaskType> object, OperationResult result) {

		ObjectWrapper<TaskType> wrapper;
		ObjectWrapperFactory owf = new ObjectWrapperFactory(this);
		try {
			wrapper = owf.createObjectWrapper("pageAdminFocus.focusDetails", null, object, ContainerStatus.MODIFYING);
		} catch (Exception ex) {
			result.recordFatalError("Couldn't get user.", ex);
			LoggingUtils.logException(LOGGER, "Couldn't load user", ex);
			wrapper = owf.createObjectWrapper("pageAdminFocus.focusDetails", null, object, null, null, ContainerStatus.MODIFYING, false);
		}
		showResult(wrapper.getResult(), false);

		return wrapper;
	}

	public boolean isEdit() {
		return edit;
	}

	public void setEdit(boolean edit) {
		this.edit = edit;
	}

	public LoadableModel<TaskDto> getTaskDtoModel() {
		return taskDtoModel;
	}

	public TaskDto getTaskDto() {
		return taskDtoModel.getObject();
	}

	public PageTaskController getController() {
		return controller;
	}

	boolean isRunnableOrRunning() {
		TaskDtoExecutionStatus exec = getTaskDto().getExecution();
		//System.out.println(this + ": state = " + exec);
		return exec == TaskDtoExecutionStatus.RUNNABLE || exec == TaskDtoExecutionStatus.RUNNING;
	}

	boolean isRunnable() {
		TaskDtoExecutionStatus exec = getTaskDto().getExecution();
		return exec == TaskDtoExecutionStatus.RUNNABLE;
	}

	boolean isRunning() {
		TaskDtoExecutionStatus exec = getTaskDto().getExecution();
		return exec == TaskDtoExecutionStatus.RUNNING;
	}

	boolean isClosed() {
		TaskDtoExecutionStatus exec = getTaskDto().getExecution();
		return exec == TaskDtoExecutionStatus.CLOSED;
	}

	boolean isSuspended() {
		TaskDtoExecutionStatus exec = getTaskDto().getExecution();
		return exec == TaskDtoExecutionStatus.SUSPENDED;
	}

	boolean isReconciliation() {
		return TaskCategory.RECONCILIATION.equals(getTaskDto().getCategory());
	}

	boolean isImportAccounts() {
		return TaskCategory.IMPORTING_ACCOUNTS.equals(getTaskDto().getCategory());
	}

	boolean isRecomputation() {
		return TaskCategory.RECOMPUTATION.equals(getTaskDto().getCategory());
	}

	boolean isExecuteChanges() {
		return TaskCategory.EXECUTE_CHANGES.equals(getTaskDto().getCategory());
	}

	boolean isLiveSync() {
		return TaskCategory.LIVE_SYNCHRONIZATION.equals(getTaskDto().getCategory());
	}

	boolean isShadowIntegrityCheck() {
		return getTaskDto().getHandlerUriList().contains(ModelPublicConstants.SHADOW_INTEGRITY_CHECK_TASK_HANDLER_URI);
	}

	boolean isFocusValidityScanner() {
		return getTaskDto().getHandlerUriList().contains(ModelPublicConstants.FOCUS_VALIDITY_SCANNER_TASK_HANDLER_URI);
	}

	boolean isTriggerScanner() {
		return getTaskDto().getHandlerUriList().contains(ModelPublicConstants.TRIGGER_SCANNER_TASK_HANDLER_URI);
	}

	boolean isDelete() {
		return getTaskDto().getHandlerUriList().contains(ModelPublicConstants.DELETE_TASK_HANDLER_URI);
	}

	boolean isBulkAction() {
		return TaskCategory.BULK_ACTIONS.equals(getTaskDto().getCategory());
	}

	boolean isRecurring() {
		return getTaskDto().getRecurring();
	}

	public TaskSummaryPanel getSummaryPanel() {
		return (TaskSummaryPanel) get(ID_SUMMARY_PANEL);
	}

	public AutoRefreshPanel getRefreshPanel() {
		return getSummaryPanel().getRefreshPanel();
	}

	public void startRefreshing() {
		refreshingBehavior.restart(null);
		refreshRefreshing();
	}

	public void stopRefreshing() {
		refreshingBehavior.stop(null);
	}

	public void refreshRefreshing() {		// necessary for some strange reason
		removeRefreshingBehavior();
		addRefreshingBehavior();
	}

	private void addRefreshingBehavior() {
		getRefreshPanel().add(refreshingBehavior);
	}

	private void removeRefreshingBehavior() {
		getRefreshPanel().remove(refreshingBehavior);
	}

	public boolean configuresWorkerThreads() {
		return isReconciliation() || isImportAccounts() || isRecomputation() || isExecuteChanges() || isShadowIntegrityCheck() || isFocusValidityScanner() || isTriggerScanner();
	}

	public boolean configuresWorkToDo() {
		return isLiveSync() || isReconciliation() || isImportAccounts() || isRecomputation() || isExecuteChanges() || isBulkAction() || isDelete() || isShadowIntegrityCheck();
	}

	public boolean configuresResourceCoordinates() {
		return isLiveSync() || isReconciliation() || isImportAccounts();
	}

	public boolean configuresObjectType() {
		return isRecomputation() || isExecuteChanges() || isDelete();
	}

	public boolean configuresObjectQuery() {
		return isRecomputation() || isExecuteChanges() || isDelete() || isShadowIntegrityCheck();
	}

	public boolean configuresObjectDelta() {
		return isExecuteChanges();
	}

	public boolean configuresScript() {
		return isBulkAction();
	}

	public boolean configuresDryRun() {
		return isLiveSync() || isReconciliation() || isImportAccounts() || isShadowIntegrityCheck();
	}

	public boolean configuresExecuteInRawMode() {
		return isExecuteChanges();
	}

	public String getTaskOid() {
		return taskOid;
	}

}
