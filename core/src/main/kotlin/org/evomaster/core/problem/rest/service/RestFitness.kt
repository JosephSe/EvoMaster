package org.evomaster.core.problem.rest.service

import com.google.inject.Inject
import org.evomaster.client.java.controller.api.EMTestUtils
import org.evomaster.client.java.controller.api.dto.AdditionalInfoDto
import org.evomaster.client.java.controller.api.dto.ExtraHeuristicDto
import org.evomaster.client.java.controller.api.dto.SutInfoDto
import org.evomaster.client.java.controller.api.dto.TestResultsDto
import org.evomaster.client.java.controller.api.dto.database.execution.ReadDbDataDto
import org.evomaster.core.database.DbActionTransformer
import org.evomaster.core.database.EmptySelects
import org.evomaster.core.problem.rest.*
import org.evomaster.core.problem.rest.auth.NoAuth
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.param.HeaderParam
import org.evomaster.core.problem.rest.param.QueryParam
import org.evomaster.core.remote.SutProblemException
import org.evomaster.core.remote.service.RemoteController
import org.evomaster.core.search.ActionResult
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.FitnessValue
import org.evomaster.core.search.gene.OptionalGene
import org.evomaster.core.search.gene.StringGene
import org.evomaster.core.search.service.FitnessFunction
import org.glassfish.jersey.client.ClientConfig
import org.glassfish.jersey.client.ClientProperties
import org.glassfish.jersey.client.HttpUrlConnectorProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.annotation.PostConstruct
import javax.ws.rs.ProcessingException
import javax.ws.rs.client.Client
import javax.ws.rs.client.ClientBuilder
import javax.ws.rs.client.Entity
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response


class RestFitness : AbstractRestFitness<RestIndividual>() {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(RestFitness::class.java)
    }

    @Inject
    private lateinit var rc: RemoteController


    @Inject
    private lateinit var sampler : RestSampler

    override fun doCalculateCoverage(individual: RestIndividual): EvaluatedIndividual<RestIndividual>? {

        rc.resetSUT()

        doInitializingActions(individual)

        individual.enforceCoherence()

        val fv = FitnessValue(individual.size().toDouble())

        val actionResults: MutableList<ActionResult> = mutableListOf()

        //used for things like chaining "location" paths
        val chainState = mutableMapOf<String, String>()

        //run the test, one action at a time
        for (i in 0 until individual.actions.size) {

            rc.registerNewAction(i)
            val a = individual.actions[i]

            var ok = false

            if (a is RestCallAction) {
                ok = handleRestCall(a, actionResults, chainState)
            } else {
                throw IllegalStateException("Cannot handle: ${a.javaClass}")
            }

            if (!ok) {
                break
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

        dto.targets.forEach { t ->

            if (t.descriptiveId != null) {
                idMapper.addMapping(t.id, t.descriptiveId)
            }

            fv.updateTarget(t.id, t.value, t.actionIndex)
        }

        handleExtra(dto, fv)

        handleResponseTargets(fv, individual.actions, actionResults)

        expandIndividual(individual, dto.additionalInfoList)

        return EvaluatedIndividual(fv, individual.copy() as RestIndividual, actionResults)

        /*
            TODO when dealing with seeding, might want to extend EvaluatedIndividual
            to keep track of AdditionalInfo
         */
    }

    override fun doInitializingActions(ind: RestIndividual) {

        if (ind.dbInitialization.none { !it.representExistingData }) {
            /*
                We are going to do an initialization of database only if there
                is data to add.
                Note that current data structure also keeps info on already
                existing data (which of course should not be re-inserted...)
             */
            return
        }

        val dto = DbActionTransformer.transform(ind.dbInitialization)

        val ok = rc.executeDatabaseCommand(dto)
        if (!ok) {
            log.warn("Failed in executing database command")
        }
    }

    override  fun hasParameterChild(a: RestCallAction): Boolean {
        return sampler.seeAvailableActions()
                .filterIsInstance<RestCallAction>()
                .map { it.path }
                .any { it.isDirectChildOf(a.path) && it.isLastElementAParameter() }
    }
}