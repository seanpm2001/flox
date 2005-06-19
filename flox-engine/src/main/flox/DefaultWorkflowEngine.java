package flox;

import flox.def.*;
import flox.def.Process;
import flox.model.*;
import flox.spi.ManualTriggerEvaluator;
import flox.spi.Action;
import flox.spi.Predicate;

import java.util.*;

import org.hibernate.criterion.Criterion;
import org.springframework.beans.factory.InitializingBean;

/**
 * Created by IntelliJ IDEA.
 * User: bob
 * Date: Mar 15, 2005
 * Time: 11:41:30 PM
 * To change this template use File | Settings | File Templates.
 */
public class DefaultWorkflowEngine
        implements InitializingBean, WorkflowEngine
{
    private WorkflowModelDao workflowModelDao;
    private StateModelDao stateModelDao;
    private ProcessLoader processLoader;
    private Map processes;

    private ManualTriggerEvaluator manualTriggerEvaluator;

    public DefaultWorkflowEngine()
    {
        this.processes = new HashMap();     
    }

    public WorkflowModelDao getWorkflowModelDao()
    {
        return workflowModelDao;
    }

    public void setWorkflowModelDao(WorkflowModelDao workflowModelDao)
    {
        this.workflowModelDao = workflowModelDao;
    }

    public StateModelDao getStateModelDao()
    {
        return stateModelDao;
    }

    public void setStateModelDao(StateModelDao stateModelDao)
    {
        this.stateModelDao = stateModelDao;
    }

    public ManualTriggerEvaluator getManualTriggerEvaluator()
    {
        return manualTriggerEvaluator;
    }

    public void setManualTriggerEvaluator(ManualTriggerEvaluator manualTriggerEvaluator)
    {
        this.manualTriggerEvaluator = manualTriggerEvaluator;
    }

    public ProcessLoader getProcessLoader()
    {
        return processLoader;
    }

    public void setProcessLoader(ProcessLoader processLoader)
    {
        this.processLoader = processLoader;
    }

    public void addProcess(Process process)
        throws DuplicateProcessException
    {
        if ( this.processes.containsKey( process.getName() ) )
        {
            throw new DuplicateProcessException( this,
                                                 process ); 
        }

        this.processes.put( process.getName(),
                            process );
    }

    public Process getProcess(String name)
        throws NoSuchProcessException
    {
        Process process = (Process) this.processes.get( name );

        if ( process == null )
        {
            throw new NoSuchProcessException( this,
                                              name );
        }

        return process;
    }

    public Collection getProcessNames()
    {
        return this.processes.keySet();
    }

    public Workflow newWorkflow(String processName)
        throws NoSuchProcessException
    {
        return newWorkflow( processName, null );
    }
    
    public Workflow newWorkflow(String processName, Object flowedObject)
        throws NoSuchProcessException
    {
        Process process = getProcess( processName );

        WorkflowModel workflowModel = new WorkflowModel( flowedObject );
        
        workflowModel.setProcessName( processName );
        getWorkflowModelDao().save( workflowModel );
    
        Workflow workflow = new Workflow( this,
                                          process,
                                          workflowModel );
        
        enterStartState( workflow );

        while( attemptTransition( workflow ) )
        {
            // do it again
        }

        return workflow;
    }
    
    public boolean attemptManualTransition(Long workflowId,
                                           String transitionName) throws NoSuchModelObjectException, NoSuchProcessException, TransitionNotManualException
    {
        Workflow workflow = getWorkflow( workflowId );
        
        Process process = workflow.getProcess();
        
        State state = workflow.getCurrentState();
        
        Transition transition = state.getTransition( transitionName );
        
        return attemptManualTransition( workflow,
                                        transition );
    }
    public boolean attemptManualTransition(Workflow workflow,
                                           Transition transition) throws TransitionNotManualException
    {
        if ( ! ( transition.getTriggerDefinition() instanceof ManualTriggerDefinition )  )
        {
            throw new TransitionNotManualException( workflow,
                                                    transition );
        }

        if ( attemptTransition( workflow,
                               transition ) )
        {
            while ( attemptTransition( workflow ) )
            {
                // do it again
            }

            return true;
        }
        else
        {
            return false;
        }
    }

    private void enterStartState(Workflow workflow)
    {
        Process process = workflow.getProcess();

        State startState = process.getStartState();

        Date now = new Date();

        enterState( now,
                    workflow,
                    startState );
    }

    private void enterState(Date now, 
                            Workflow workflow, 
                            State state)
    {
        StateModel stateModel = new StateModel();

        stateModel.setName( state.getName() );
        stateModel.setEnteredDate( now );
        stateModel.setWorkflow( workflow.getModel() );

        getStateModelDao().save( stateModel );
        
        workflow.getModel().setCurrentState( stateModel );
        
        getWorkflowModelDao().save( workflow.getModel() );
        Action action = state.getAction();

        if ( action != null )
        {
            try
            {
                action.execute( workflow,
                                null );
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        if ( state.getTransitions().isEmpty() )
        {
            exitState( now,
                       workflow );
        }
    }
    
    private void exitState(Date now,
                           Workflow workflow)
    {
        try
        {
            StateModel stateModel = getStateModelDao().getCurrentState( workflow.getModel() );
            stateModel.setExitedDate( now );
            getStateModelDao().save( stateModel );
        }
        catch (NoSuchModelObjectException e)
        {
            throw new WorkflowIntegrityException( workflow,
                                                  e );
        }
    }
    
    private boolean attemptTransition(Workflow workflow)
    {
        State state = workflow.getCurrentState();

        if ( state == null )
        {
            return false;
        }

        List<Transition> transitions = state.getTransitions();
        
        for ( Iterator<Transition> transIter = transitions.iterator(); transIter.hasNext(); )
        {
            Transition transition = transIter.next();

            TriggerDefinition triggerDef = transition.getTriggerDefinition();

            if ( triggerDef == null || triggerDef instanceof AutomaticTriggerDefinition )
            {
                boolean result = attemptTransition( workflow,
                                                    transition );

                if ( result )
                {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private boolean attemptTransition(Workflow workflow,
                                      Transition transition)
    {
        State state = workflow.getCurrentState();
        List<Transition> transitions = state.getTransitions();
        
        if ( ! transitions.contains( transition ) )
        {
            throw new WorkflowIntegrityException( workflow, null );
        }
        
        TriggerDefinition triggerDef = transition.getTriggerDefinition();
        
        Predicate predicate = transition.getPredicate();

        if ( ( predicate == null )
                    ||
        ( predicate.evaluate( workflow,
                              null ) ) )
        {
            followTransition( workflow,
                              transition );
                
            return true;
        }
        
        return false;
    }
    
    private void followTransition(Workflow workflow,
                                  Transition transition)
    {
        Date now = new Date();

        exitState( now,
                   workflow );

        Action action = transition.getAction();

        if ( action != null )
        {
            try
            {
                action.execute( workflow,
                                null );
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        enterState( now,
                    workflow,
                    transition.getDestination() );
    }

    public Workflow getWorkflow(String processName,
                                Class flowedObjectClass,
                                Criterion flowedObjectCriterion)
            throws NoSuchProcessException, NoSuchModelObjectException
    {
        Process process = getProcess( processName );

        WorkflowModel workflowModel = getWorkflowModelDao().get( processName,
                                                                 flowedObjectClass,
                                                                 flowedObjectCriterion );

        Workflow workflow = new Workflow( this,
                                          process,
                                          workflowModel );

        return workflow;
    }
    
    public Workflow getWorkflow(Long id) throws NoSuchModelObjectException, NoSuchProcessException
    {
        WorkflowModel wfModel = getWorkflowModelDao().get( id );
        
        Process process = getProcess( wfModel.getProcessName() );
        
        return new Workflow( this, process, wfModel );
    }

    public List getWorkflows(String processName) throws NoSuchProcessException
    {
        Process process = getProcess( processName );

        List models = getWorkflowModelDao().getAll( processName );
        
        List flows = new ArrayList( models.size() );
        
        for ( Iterator modelIter = models.iterator(); modelIter.hasNext(); )
        {
            WorkflowModel model = (WorkflowModel) modelIter.next();
            
            Workflow flow = new Workflow( this, process, model );
            
            flows.add( flow );
        }
        
        return flows;
    }
    
    public List getWorkflows(String processName, String currentStateName) throws NoSuchProcessException, NoSuchStateException
    {
        Process process = getProcess( processName );
        
        State currentState = process.getState( currentStateName );
        
        List models = getWorkflowModelDao().getAll( processName, currentState );
        
        List flows = new ArrayList( models.size() );
        
        for ( Iterator modelIter = models.iterator(); modelIter.hasNext(); )
        {
            WorkflowModel model = (WorkflowModel) modelIter.next();
            
            Workflow flow = new Workflow( this, process, model );
            
            flows.add( flow );
        }
        
        return flows;
    }

    State getCurrentState(Workflow workflow)
    {
        try
        {
            StateModel model = getStateModelDao().getCurrentState( workflow.getModel() );

            State state = workflow.getProcess().getState( model.getName() );

            return state;
        }
        catch (NoSuchStateException e)
        {
            throw new WorkflowIntegrityException( workflow,
                                                  e );
        }
        catch (NoSuchModelObjectException e)
        {
            return null;
        }
    }

    List<Transition> getCurrentTransitions(Workflow workflow)
    {
        State state = getCurrentState( workflow );

        if ( state != null )
        {
            return state.getTransitions();
        }
        
        return new ArrayList<Transition>();
    }

    public List<Transition> getAvailableCurrentTransitions(Workflow workflow)
    {
        List<Transition> available = new LinkedList<Transition>( getCurrentTransitions( workflow ) );

        for ( Iterator<Transition> transIter = available.iterator(); transIter.hasNext(); )
        {
            Transition transition = transIter.next();

            if ( transition.getPredicate() != null ) 
            {
                if ( ! transition.getPredicate().evaluate( workflow,
                                                           null ) )
                {
                    transIter.remove();
                }
            }
        }

        return Collections.unmodifiableList( available );
    }

    public List<StateModel> getStateSequence(Workflow workflow)
    {
        return getStateModelDao().getStateSequence( workflow.getModel() );
    }

    public void afterPropertiesSet() throws Exception
    {
        if ( this.processLoader != null )
        {   
            this.processLoader.loadProcesses( this );
        }
    }
}

