package org.evomaster.core.problem.rest.resource.service


import com.google.inject.Inject
import org.evomaster.core.database.DbAction
import org.evomaster.core.database.DbActionTransformer
import org.evomaster.core.problem.rest.RestAction
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.SampleType
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.problem.rest.resource.model.RestResourceCalls
import org.evomaster.core.problem.rest.service.AbstractRestFitness
import org.evomaster.core.remote.service.RemoteController
import org.evomaster.core.search.ActionResult
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.FitnessValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ResourceRestFitness : AbstractRestFitness<ResourceRestIndividual>() {

    @Inject
    private lateinit var rc: RemoteController

    @Inject
    private lateinit var sampler : ResourceRestSampler

    @Inject
    private lateinit var rm: ResourceManageService

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ResourceRestFitness::class.java)
    }

    /*
        add db check in term of each abstract resource
     */
    override fun doCalculateCoverage(individual: ResourceRestIndividual): EvaluatedIndividual<ResourceRestIndividual>? {

        rc.resetSUT()

        val fv = FitnessValue(individual.size().toDouble())

        val actionResults: MutableList<ActionResult> = mutableListOf()

        //used for things like chaining "location" paths
        val chainState = mutableMapOf<String, String>()

        //run the test, one action at a time
        var indexOfAction = 0

        if(individual.sampleType == SampleType.SMART_RESOURCE_WITH_DEP)
            doInitializingCalls(individual.getResourceCalls().flatMap { it.dbActions })

        for (call in individual.getResourceCalls()) {

            if(individual.sampleType == SampleType.SMART_RESOURCE_WITHOUT_DEP)
                doInitializingCalls(call.dbActions)

            for (a in call.actions){
                rc.registerNewAction(indexOfAction)

                var ok = false

                if (a is RestCallAction) {
                    ok = handleRestCall(a, actionResults, chainState)
                } else {
                    throw IllegalStateException("Cannot handle: ${a.javaClass}")
                }

                if (!ok) {
                    break
                }
                indexOfAction++
            }

        }

        /*
            We cannot request all non-covered targets, because:
            1) performance hit
            2) might not be possible to have a too long URL
         */
        //TODO prioritized list
        val ids = randomness.choose(archive.notCoveredTargets(), 100)

        val dto = rc.getTestResults(ids)
        if (dto == null) {
            log.warn("Cannot retrieve coverage")
            return null
        }

        rm.updateResourceTables(individual, dto)

        dto.targets.forEach { t ->

            if (t.descriptiveId != null) {
                idMapper.addMapping(t.id, t.descriptiveId)
            }

            fv.updateTarget(t.id, t.value, t.actionIndex)
        }

        handleExtra(dto, fv)

        handleResponseTargets(fv, individual.seeActions() as MutableList<RestAction>, actionResults)

        expandIndividual(individual, dto.additionalInfoList)

        return if(config.enableTrackEvaluatedIndividual)
            EvaluatedIndividual(fv, individual.copy() as ResourceRestIndividual, actionResults, null, mutableListOf(), mutableListOf(), withImpacts = (config.probOfArchiveMutation > 0.0))
        else EvaluatedIndividual(fv, individual.copy() as ResourceRestIndividual, actionResults, withImpacts = (config.probOfArchiveMutation > 0.0))

        /*
            TODO when dealing with seeding, might want to extend EvaluatedIndividual
            to keep track of AdditionalInfo
         */
    }

    private fun doInitializingCalls(allDbActions : List<DbAction>) {

        if (allDbActions.isEmpty()) {
            return
        }

        if (allDbActions.none { !it.representExistingData }) {
            /*
                We are going to do an initialization of database only if there
                is data to add.
                Note that current data structure also keeps info on already
                existing data (which of course should not be re-inserted...)
             */
            return
        }

        val dto = DbActionTransformer.transform(allDbActions)

        val ok = rc.executeDatabaseCommand(dto)
        if (!ok) {
            log.warn("Failed in executing database command")
        }
    }

    override fun hasParameterChild(a: RestCallAction): Boolean {
        return sampler.seeAvailableActions()
                .filterIsInstance<RestCallAction>()
                .map { it.path }
                .any { it.isDirectChildOf(a.path) && it.isLastElementAParameter() }
    }
}