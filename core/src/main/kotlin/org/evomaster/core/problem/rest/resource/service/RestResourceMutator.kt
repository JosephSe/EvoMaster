package org.evomaster.core.problem.rest.resource.service

import com.google.inject.Inject
import org.evomaster.core.problem.rest.resource.model.RestResourceIndividual
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.service.Archive
import org.evomaster.core.search.service.mutator.StandardMutator

/**
 * resource-based mutator
 * i.e., the standard mutator handles resource-based rest individual
 */
class ResourceRestMutator : StandardMutator<RestResourceIndividual>() {

    @Inject
    private lateinit var rm :ResourceManageService

    @Inject
    private lateinit var dm :ResourceDepManageService

    @Inject
    private lateinit var archive: Archive<RestResourceIndividual>


    override fun postActionAfterMutation(mutatedIndividual: RestResourceIndividual) {
        super.postActionAfterMutation(mutatedIndividual)
        mutatedIndividual.getResourceCalls().forEach { rm.repairRestResourceCalls(it) }
        mutatedIndividual.repairDBActions()
    }

    override fun doesStructureMutation(individual : RestResourceIndividual): Boolean {

        return individual.canMutateStructure() &&
                (!dm.onlyIndependentResource()) && // if all resources are asserted independent, there is no point to do structure mutation
                config.maxTestSize > 1 &&
                randomness.nextBoolean(config.structureMutationProbability)
    }

    override fun genesToMutation(individual: RestResourceIndividual, evi : EvaluatedIndividual<RestResourceIndividual>): List<Gene> {
        //if data of resource call is existing from db, select other row
        val selectAction = individual.getResourceCalls().filter { it.dbActions.isNotEmpty() && it.dbActions.last().representExistingData }
        if(selectAction.isNotEmpty())
            return randomness.choose(selectAction).seeGenes()
        return individual.getResourceCalls().flatMap { it.seeGenes() }.filter(Gene::isMutable)
    }

    override fun update(previous: EvaluatedIndividual<RestResourceIndividual>, mutated: EvaluatedIndividual<RestResourceIndividual>, mutatedGenes : MutableList<Gene>) {
        //update resource dependency after mutating strcuture of the resource-based individual
        if(mutatedGenes.isEmpty() && (previous.individual.getResourceCalls().size > 1 || mutated.individual.getResourceCalls().size > 1) && config.probOfEnablingResourceDependencyHeuristics > 0){
            val isWorse = previous.fitness.subsumes(mutated.fitness, archive.notCoveredTargets())
            val isBetter = archive.wouldReachNewTarget(mutated) || !isWorse
            dm.detectDependencyAfterStructureMutation(previous, mutated, if(isBetter) 1 else if(isWorse) -1 else 0)
        }

        /*
         TODO Man update resource dependency after do standard mutation?
         */
    }

}