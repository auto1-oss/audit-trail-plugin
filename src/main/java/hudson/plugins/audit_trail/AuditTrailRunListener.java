package hudson.plugins.audit_trail;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.inject.Inject;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @author Pierre Beitz
 */
@Extension
public class AuditTrailRunListener extends RunListener<Run> {
    private static final Logger LOGGER = Logger.getLogger(AuditTrailRunListener.class.getName());

    private static final String MASKED = "****";
    private static final String BRANCH_INDEXING_CAUSE = "Branch indexing";
    private static final String TIMER_CAUSE = "Started by timer";
    static final String UNKNOWN_NODE = "#unknown#";
    static final String BUILT_IN_NODE = "built-in";

    @Inject
    AuditTrailPlugin configuration;

    public AuditTrailRunListener() {
        super(Run.class);
    }

    @Override
    public void onStarted(Run run, TaskListener listener) {
        if (configuration.shouldLogBuildCause() && !shouldSkipLogging(run)) {
            StringBuilder builder = new StringBuilder(100);
            dumpCauses(run, builder);
            dumpParameters(run, builder);

            for (AuditLogger logger : configuration.getLoggers()) {
                logger.log(run.getParent().getUrl() + " #" + run.getNumber() + ' ' + builder.toString());
            }
        }
    }

    @Override
    public void onFinalized(Run run) {
        if (configuration.shouldLogBuildCause() && !shouldSkipLogging(run)) {
            StringBuilder builder = new StringBuilder(100);
            dumpCauses(run, builder);
            dumpParameters(run, builder);

            for (AuditLogger logger : configuration.getLoggers()) {
                String message = run.getFullDisplayName() + " "
                        + builder.toString() + " on node "
                        + buildNodeName(run) + " started at "
                        + run.getTimestampString2() + " completed in "
                        + run.getDuration() + "ms" + " completed: "
                        + run.getResult();
                logger.log(message);
            }
        }
    }

    private void dumpParameters(Run<?, ?> run, StringBuilder builder) {
        builder.append(", Parameters:[");
        ParametersAction parameters = run.getAction(ParametersAction.class);
        if (parameters != null) {
            builder.append(StreamSupport.stream(parameters.spliterator(), false)
                    .map(this::prettyPrintParameter)
                    .collect(Collectors.joining(", ")));
        }
        builder.append("]");
    }

    private String prettyPrintParameter(ParameterValue param) {
        return param.getName() + ": {" + (param.isSensitive() ? MASKED : param.getValue()) + "}";
    }

    private void dumpCauses(Run<?, ?> run, StringBuilder buf) {
        for (CauseAction action : run.getActions(CauseAction.class)) {
            for (Cause cause : action.getCauses()) {
                if (buf.length() > 0) buf.append(", ");
                buf.append(cause.getShortDescription());
            }
        }
        if (buf.length() == 0) buf.append("Started");
    }

    private boolean shouldSkipLogging(Run<?, ?> run) {
        for (CauseAction action : run.getActions(CauseAction.class)) {
            for (Cause cause : action.getCauses()) {
                String description = cause.getShortDescription();
                if (description != null
                        && (description.contains(BRANCH_INDEXING_CAUSE) || description.contains(TIMER_CAUSE))) {
                    return true;
                }
            }
        }
        return false;
    }

    String buildNodeName(Run<?, ?> run) {
        try {
            if (run instanceof AbstractBuild) {
                return getNodeNameFromAbstractBuild((AbstractBuild<?, ?>) run);
            }

            if (run instanceof WorkflowRun) {
                WorkflowRun workflowRun = (WorkflowRun) run;
                String agentName = getPipelineAgentName(workflowRun);
                String label = getPipelineNodeLabel(workflowRun);

                if (agentName != null && label != null) {
                    // If agent name matches any of the labels (comma-separated)
                    for (String singleLabel : label.split(",")) {
                        if (singleLabel.trim().equals(agentName)) {
                            return agentName;
                        }
                    }
                    return String.format("%s (%s)", agentName, label);
                }
                if (agentName != null) return agentName;
                if (label != null) return label; // Changed: Just return the label directly
            }

            String genericNodeName = getNodeNameGeneric(run);
            if (genericNodeName != null) return genericNodeName;

            return UNKNOWN_NODE;
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "Failed to determine node name", e);
            return UNKNOWN_NODE;
        }
    }

    private String getPipelineAgentName(WorkflowRun run) {
        if (run == null) {
            return null;
        }

        try {
            // First try to get the actual node name where stages executed
            FlowExecution execution = run.getExecution();
            if (execution != null) {
                for (FlowNode head : execution.getCurrentHeads()) {
                    String nodeName = getNodeNameFromFlowNode(head);
                    if (nodeName != null && !nodeName.equals("End of Pipeline")) {
                        return nodeName;
                    }
                }
            }

            // Fallback to executor information
            Executor executor = run.getExecutor();
            if (executor != null && executor.getOwner() != null) {
                String computerName = executor.getOwner().getName();
                if (computerName != null && !computerName.isEmpty()) {
                    return computerName;
                }
            }

            return null;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error getting pipeline agent name", e);
            return null;
        }
    }

    private String getNodeNameFromFlowNode(FlowNode node) {
        if (node == null) {
            return null;
        }

        // Check for node-specific information
        ArgumentsAction args = node.getAction(ArgumentsAction.class);
        if (args != null && args.getArguments() != null) {
            Object nodeName = args.getArguments().get("node");
            if (nodeName != null) {
                return nodeName.toString();
            }
        }

        // Check for built-in computer name
        try {
            Method getDisplayName = node.getClass().getMethod("getDisplayName");
            Object result = getDisplayName.invoke(node);
            return result != null ? result.toString() : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            LOGGER.log(Level.FINEST, "Could not get display name via reflection", e);
            return null;
        }
    }

    private String getNodeNameFromAbstractBuild(AbstractBuild<?, ?> build) {
        Node node = build.getBuiltOn();
        if (node != null) {
            return node.getDisplayName();
        }
        return build.getBuiltOnStr() != null ? build.getBuiltOnStr() : BUILT_IN_NODE;
    }

    private String getNodeNameGeneric(Run<?, ?> run) {
        try {
            Method getBuiltOnStrMethod = run.getClass().getMethod("getBuiltOnStr");
            String nodeName = (String) getBuiltOnStrMethod.invoke(run);
            if (nodeName != null && !nodeName.isEmpty()) {
                return nodeName;
            }
        } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            // Method not available or inaccessible - expected for many job types
        }
        return null;
    }

    // For Pipeline jobs: extract agent label(s) from the flow graph
    private String getPipelineNodeLabel(Run<?, ?> run) {
        if (!(run instanceof WorkflowRun)) {
            return null;
        }
        WorkflowRun workflowRun = (WorkflowRun) run;
        FlowExecution execution = workflowRun.getExecution();
        if (execution == null) {
            return null;
        }
        try {
            Set<String> nodeLabels = new HashSet<>();
            Set<String> visited = new HashSet<>();
            for (FlowNode node : execution.getCurrentHeads()) {
                collectNodeLabels(node, nodeLabels, visited);
            }
            if (!nodeLabels.isEmpty()) {
                // If multiple nodes, join with comma
                return String.join(",", nodeLabels);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error while extracting pipeline node labels: {0}", e.toString());
        }
        return null;
    }

    private void collectNodeLabels(FlowNode node, Set<String> nodeLabels, Set<String> visited) {
        if (!visited.add(node.getId())) return;
        if (node instanceof StepStartNode) {
            StepStartNode startNode = (StepStartNode) node;
            if ("node".equals(startNode.getDisplayFunctionName())) {
                ArgumentsAction args = startNode.getAction(ArgumentsAction.class);
                if (args != null) {
                    Object labelObj = args.getArguments().get("label");
                    if (labelObj != null) {
                        String label = labelObj.toString();
                        if (!label.isEmpty()) {
                            nodeLabels.add(label);
                        }
                    }
                }
            }
        }
        for (FlowNode parent : node.getParents()) {
            collectNodeLabels(parent, nodeLabels, visited);
        }
    }
}
