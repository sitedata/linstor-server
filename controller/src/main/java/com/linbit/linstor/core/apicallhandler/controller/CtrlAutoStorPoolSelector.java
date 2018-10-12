package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.ImplementationError;
import com.linbit.linstor.LinstorParsingUtils;
import com.linbit.linstor.Node;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.StorPoolDefinition;
import com.linbit.linstor.StorPoolName;
import com.linbit.linstor.Volume;
import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.core.CoreModule.ResourceDefinitionMap;
import com.linbit.linstor.core.CoreModule.StorPoolDefinitionMap;
import com.linbit.linstor.core.apicallhandler.controller.helpers.StorPoolHelper;
import com.linbit.linstor.core.apicallhandler.response.ApiAccessDeniedException;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.AccessType;
import com.linbit.linstor.storage.DisklessDriverKind;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class CtrlAutoStorPoolSelector
{
    private final StorPoolDefinitionMap storPoolDfnMap;
    private final ResourceDefinitionMap rscDfnMap;
    private final Provider<AccessContext> peerAccCtx;
    private final AccessContext apiAccCtx;

    @Inject
    public CtrlAutoStorPoolSelector(
        ResourceDefinitionMap rscDfnMapRef,
        StorPoolDefinitionMap storPoolDfnMapRef,
        @PeerContext Provider<AccessContext> peerAccCtxRef,
        @SystemContext AccessContext apiAccCtxRef
    )
    {
        rscDfnMap = rscDfnMapRef;
        storPoolDfnMap = storPoolDfnMapRef;
        peerAccCtx = peerAccCtxRef;
        apiAccCtx = apiAccCtxRef;
    }

    public List<Candidate> getCandidateList(
        final long rscSize,
        final AutoStorPoolSelectorConfig selectFilter,
        final NodeSelectionStrategy nodeSelectionStrategy
    )
    {
        Map<StorPoolName, List<Node>> nodes = buildInitialCandidateList(rscSize);

        nodes = filterByStorPoolName(selectFilter, nodes);

        nodes = filterByDoNotPlaceWithResource(selectFilter, nodes);

        // this method already trims the node-list to placeCount.
        List<Candidate> candidateList = filterByReplicasOn(
            selectFilter,
            nodes,
            nodeSelectionStrategy
        );

        return candidateList;
    }

    private Map<StorPoolName, List<Node>> buildInitialCandidateList(final long rscSize)
    {
         /*
          * build a map of storage pools
          * * where the user has access to
          * * that have enough free space
          * * that are not diskless
          */
         HashMap<StorPoolName, List<StorPool>> tmpMap = storPoolDfnMap.values().stream()
            // filter for user access on storPoolDfn
            .filter(storPoolDfn -> storPoolDfn.getObjProt().queryAccess(peerAccCtx.get()).hasAccess(AccessType.USE))
            .flatMap(this::getStorPoolStream)
            // filter for diskless
            .filter(storPool -> !(storPool.getDriverKind() instanceof DisklessDriverKind))
            // filter for user access on node
            .filter(storPool -> storPool.getNode().getObjProt().queryAccess(peerAccCtx.get()).hasAccess(AccessType.USE))
            // filter for node connected
            .filter(storPool -> getPeerPrivileged(storPool).isConnected())
            // filter for enough free space
            .filter(storPool -> poolHasSpaceFor(storPool, rscSize))
            .collect(
                Collectors.groupingBy(
                    StorPool::getName,
                    HashMap::new, // enforce HashMap-implementation,
                    Collectors.toList()
                )
            );
         HashMap<StorPoolName, List<Node>> candidateMap = new HashMap<>();
         for (Entry<StorPoolName, List<StorPool>> entry : tmpMap.entrySet())
         {
             candidateMap.put(
                 entry.getKey(),
                 entry.getValue().stream().map(StorPool::getNode).collect(Collectors.toList())
             );
         }

        return candidateMap;
    }

    private boolean poolHasSpaceFor(StorPool storPool, long rscSize)
    {
        boolean hasSpace;
        try
        {
            hasSpace = storPool.getDriverKind().usesThinProvisioning() ||
                storPool.getFreeSpaceTracker()
                    .getFreeSpaceCurrentEstimation(peerAccCtx.get())
                    .orElse(0L) >= rscSize;
        }
        catch (AccessDeniedException exc)
        {
            throw queryFreeSpaceAccDenied(exc, storPool.getNode().getName(), storPool.getName());
        }
        return hasSpace;
    }

    private List<String> toUpperList(List<String> list)
    {
        return list.stream()
            .map(String::toUpperCase)
            .collect(Collectors.toList());
    }

    private List<String> getRscNameUpperStrFromRegex(String rscRegexStr)
    {
        Pattern rscRegexPattern = Pattern.compile(
            rscRegexStr,
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        return rscDfnMap.keySet().stream()
            .map(rscName -> rscName.value)
            .filter(rscName -> rscRegexPattern.matcher(rscName).find())
            .collect(Collectors.toList());
    }

    private Stream<StorPool> getStorPoolStream(StorPoolDefinition storPoolDefinition)
    {
        Stream<StorPool> stream;
        try
        {
            stream = storPoolDefinition.streamStorPools(peerAccCtx.get());
        }
        catch (AccessDeniedException exc)
        {
            throw new ApiAccessDeniedException(
                exc,
                "stream storage pools of storage pool definition '" +
                    storPoolDefinition.getName().displayValue + "'",
                ApiConsts.FAIL_ACC_DENIED_STOR_POOL_DFN
            );
        }
        return stream;
    }

    private Map<StorPoolName, List<Node>> filterByStorPoolName(
        final AutoStorPoolSelectorConfig selectFilter,
        Map<StorPoolName, List<Node>> nodes
    )
    {
        Map<StorPoolName, List<Node>> ret = new HashMap<>();
        String forcedStorPoolName = selectFilter.getStorPoolNameStr();
        if (forcedStorPoolName != null)
        {
            StorPoolName storPoolName = LinstorParsingUtils.asStorPoolName(selectFilter.getStorPoolNameStr());
            ret.put(storPoolName, nodes.get(storPoolName)); // skip all other entries
        }
        else
        {
            ret.putAll(nodes);
        }
        return ret;
    }

    private StorPool getStorPoolPrivileged(Node node, StorPoolName storPoolName)
    {
        StorPool storPool;
        try
        {
            storPool = node.getStorPool(apiAccCtx, storPoolName);
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        return storPool;
    }

    private Peer getPeerPrivileged(StorPool storPool)
    {
        Peer peer;
        try
        {
            peer = storPool.getNode().getPeer(apiAccCtx);
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        return peer;
    }

    private Optional<Long> getFreeSpace(Node node, StorPoolName storPoolName)
    {
        Optional<Long> freeSpace;
        if (node == null || storPoolName == null)
        {
            freeSpace = Optional.empty();
        }
        else
        {
            try
            {
                freeSpace = node.getStorPool(peerAccCtx.get(), storPoolName)
                    .getFreeSpaceTracker()
                    .getFreeSpaceCurrentEstimation(peerAccCtx.get());
            }
            catch (AccessDeniedException exc)
            {
                throw queryFreeSpaceAccDenied(exc, node.getName(), storPoolName);
            }
        }
        return freeSpace;
    }

    private ApiAccessDeniedException queryFreeSpaceAccDenied(
        AccessDeniedException exc,
        NodeName nodeName,
        StorPoolName storPoolName
    )
    {
        return new ApiAccessDeniedException(
            exc,
            "query free space of " + StorPoolHelper.getStorPoolDescriptionInline(
                nodeName.displayValue,
                storPoolName.displayValue
            ),
            ApiConsts.FAIL_ACC_DENIED_STOR_POOL
        );
    }

    private Map<StorPoolName, List<Node>> filterByDoNotPlaceWithResource(
        final AutoStorPoolSelectorConfig selectFilter,
        Map<StorPoolName, List<Node>> nodes
    )
    {
        List<String> notPlaceWithRscList = new ArrayList<>(toUpperList(selectFilter.getNotPlaceWithRscList()));
        String notPlaceWithRscRegexStr = selectFilter.getNotPlaceWithRscRegex();
        if (notPlaceWithRscRegexStr != null)
        {
            notPlaceWithRscList.addAll(getRscNameUpperStrFromRegex(notPlaceWithRscRegexStr));
        }

        Map<StorPoolName, List<Node>> candidates = null;
        try
        {
            // try to consider the "do not place with resource" argument on node level.
            candidates = filterByRscNameStr(nodes, notPlaceWithRscList);
            if (candidates.isEmpty())
            {
                // if that didn't work, try to consider the "do not place with resource" argument on storPool level
                for (Entry<StorPoolName, List<Node>> entry : nodes.entrySet())
                {
                    // build a list of storPools that have at least one of the "do not place with resource" resources.
                    List<Node> nodesToRemove = new ArrayList<>();
                    for (Node node : entry.getValue())
                    {
                        Collection<Volume> volumes = node.getStorPool(apiAccCtx, entry.getKey()).getVolumes(apiAccCtx);
                        for (Volume vlm : volumes)
                        {
                            if (notPlaceWithRscList.contains(vlm.getResourceDefinition().getName().value))
                            {
                                nodesToRemove.add(node);
                                break;
                            }
                        }
                    }
                    // remove that storPools
                    entry.getValue().removeAll(nodesToRemove);
                }

                // We already applied the filtering on storPool level. That means we can re-run the
                // filterCandidates with no "do not place with resource" restriction on node-level, as we are
                // already only considering the filtered storPools.
                candidates = filterByRscNameStr(nodes, Collections.emptyList());
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ImplementationError(accDeniedExc);
        }
        return candidates;
    }

    /*
     * We can NOT return a Map<StorPoolName, List<Node>> here anymore, as we might have multiple
     * node-lists per storPoolName. For example: if one of our map entry-values contain 10 nodes,
     * where 5 of them have property "A"="1" and the others have "A"="2", with a placeCount = 5 and
     * a "replicasOnSamePropList" containing "A".
     * As a result of this filter-method, we will emit 2 candidates for the given storPoolName.
     *
     * That means we have 2 choices. Either return a List<Candidate> or a
     * Map<StorPoolName, List<List<Node>>>.
     */
    private List<Candidate> filterByReplicasOn(
        final AutoStorPoolSelectorConfig selectFilter,
        Map<StorPoolName, List<Node>> candidatesIn,
        NodeSelectionStrategy nodeSelectionStartegy
    )
    {
        List<String> replicasOnSamePropList = selectFilter.getReplicasOnSameList();
        List<String> replicasOnDiffParamList = selectFilter.getReplicasOnDifferentList();
        int placeCount = selectFilter.getPlaceCount();

        List<Candidate> candidatesOut = new ArrayList<>();
        try
        {
            for (Entry<StorPoolName, List<Node>> candidateEntry : candidatesIn.entrySet())
            {
                List<Node> candidateNodes = candidateEntry.getValue();

                // Gather the prop values for the props that need to be the same
                Map<NodeName, Map<String, String>> propsForNodes = new HashMap<>();
                for (Node node : candidateNodes)
                {
                    Map<String, String> props = new HashMap<>();
                    for (String samePropKey : replicasOnSamePropList)
                    {
                        props.put(samePropKey, node.getProps(peerAccCtx.get()).getProp(samePropKey));
                    }
                    propsForNodes.put(node.getName(), props);
                }

                // Form groups of nodes where all the prop values match
                Collection<List<Node>> nodeGroups = candidateNodes.stream()
                    .collect(Collectors.groupingBy(node -> propsForNodes.get(node.getName())))
                    .values();

                // Eliminate elements from the groups until the nodes in each group differ in all the props that need
                // to be different
                for (List<Node> nodeGroup : nodeGroups)
                {
                    // Sort the nodes within the group so that the most preferred nodes are chosen first
                    Comparator<Node> nodeComparator = nodeSelectionStartegy.makeComparator(
                        candidateEntry.getKey(),
                        peerAccCtx.get()
                    );
                    Collection<Node> remainingNodes = nodeGroup.stream()
                        // descending order of preference
                        .sorted(nodeComparator.reversed())
                        .collect(Collectors.toCollection(ArrayList::new));
                    for (String diffPropKey : replicasOnDiffParamList)
                    {
                        HashMap<String, Node> usedValues = new HashMap<>();
                        for (Node node : remainingNodes)
                        {
                            String propValue = node.getProps(peerAccCtx.get()).getProp(diffPropKey);
                            if (!usedValues.containsKey(propValue))
                            {
                                usedValues.put(propValue, node);
                            }
                        }
                        remainingNodes = usedValues.values();
                    }

                    addCandidate(candidatesOut, candidateEntry.getKey(), remainingNodes, placeCount);
                }
            }
        }
        catch (InvalidKeyException invalidKeyExc)
        {
            throw new ApiRcException(ApiCallRcImpl.simpleEntry(
                ApiConsts.FAIL_INVLD_PROP,
                "The property key '" + invalidKeyExc.invalidKey + "' is invalid."
            ), invalidKeyExc);
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc); // should have been thrown long ago
        }
        return candidatesOut;
    }

    private void addCandidate(
        List<Candidate> targetList,
        StorPoolName storPoolName,
        Collection<Node> nodeListRef,
        int nodeCount
    )
    {
        if (nodeListRef.size() >= nodeCount)
        {
            List<Node> nodeList = new ArrayList<>(nodeListRef).subList(0, nodeCount);

            targetList.add(
                new Candidate(
                    storPoolName,
                    nodeList,
                    nodeList.stream()
                        .map(node -> getFreeSpace(node, storPoolName).orElse(0L))
                        .min(Long::compare)
                        .orElse(0L),
                    nodeList.stream()
                        .allMatch(node ->
                            getStorPoolPrivileged(node, storPoolName).getDriverKind().usesThinProvisioning())
                )
            );
        }
    }

    public static Comparator<Candidate> mostRemainingSpaceCandidateStrategy(AccessContext accCtx)
    {
        Comparator<StorPool> thickComparator = Comparator.<StorPool, Boolean>comparing(
            storPool -> storPool.getDriverKind().usesThinProvisioning()
        ).reversed();

        Comparator<StorPool> freeSpaceComparator = Comparator.comparingLong(
            storPool -> getFreeSpaceCurrentEstimationPrivileged(accCtx, storPool).orElse(0L)
        );

        return comparingWithComparator(
            candidate -> getStorPoolPrivileged(accCtx, candidate.nodes.get(0), candidate.storPoolName),
            thickComparator.thenComparing(freeSpaceComparator)
        );
    }

    public static Comparator<Node> mostRemainingSpaceNodeStrategy(
        StorPoolName storPoolName,
        AccessContext accCtx
    )
    {
        return Comparator.comparingLong(node ->
            getFreeSpaceCurrentEstimationPrivileged(
                accCtx,
                getStorPoolPrivileged(accCtx, node, storPoolName)
            ).orElse(0L)
        );
    }

    private static StorPool getStorPoolPrivileged(AccessContext accCtx, Node node, StorPoolName storPoolName)
    {
        StorPool storPool;
        try
        {
            storPool = node.getStorPool(accCtx, storPoolName);
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        return storPool;
    }

    private static Optional<Long> getFreeSpaceCurrentEstimationPrivileged(
        AccessContext accCtx, StorPool storPool)
    {
        Optional<Long> freeSpaceCurrentEstimation;
        try
        {
            freeSpaceCurrentEstimation = storPool.getFreeSpaceTracker().getFreeSpaceCurrentEstimation(accCtx);
        }
        catch (AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        return freeSpaceCurrentEstimation;
    }

    private static <T, U> Comparator<T> comparingWithComparator(Function<T, U> mapper, Comparator<U> comparator)
    {
        return (t1, t2) -> comparator.compare(mapper.apply(t1), mapper.apply(t2));
    }

    private Map<StorPoolName, List<Node>> filterByRscNameStr(
        Map<StorPoolName, List<Node>> nodes,
        List<String> notPlaceWithRscList
    )
    {
        Map<StorPoolName, List<Node>> ret = new HashMap<>();
        for (Entry<StorPoolName, List<Node>> entry: nodes.entrySet())
        {
            List<Node> nodeCandidates = entry.getValue().stream()
                .sorted(Comparator.comparing(node -> getFreeSpace(node, entry.getKey()).orElse(0L)))
                .filter(node -> hasNoResourceOf(node, notPlaceWithRscList))
                .collect(Collectors.toList());

            if (!nodeCandidates.isEmpty())
            {
                ret.put(entry.getKey(), nodeCandidates);
            }
        }
        return ret;
    }

    private boolean hasNoResourceOf(Node node, List<String> notPlaceWithRscList)
    {
        boolean hasNoResourceOf = false;
        try
        {
            hasNoResourceOf = node.streamResources(peerAccCtx.get())
                .map(rsc -> rsc.getDefinition().getName().value)
                .noneMatch(notPlaceWithRscList::contains);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ImplementationError(accDeniedExc);
        }
        return hasNoResourceOf;
    }

    public static class Candidate
    {
        final StorPoolName storPoolName;
        final List<Node> nodes;
        final long sizeAfterDeployment;
        final boolean allThin;

        Candidate(
            StorPoolName storPoolNameRef,
            List<Node> nodesRef,
            long sizeAfterDeploymentRef,
            boolean allThinRef
        )
        {
            storPoolName = storPoolNameRef;
            nodes = nodesRef;
            sizeAfterDeployment = sizeAfterDeploymentRef;
            allThin = allThinRef;
        }

        public StorPoolName getStorPoolName()
        {
            return storPoolName;
        }

        public List<Node> getNodes()
        {
            return nodes;
        }

        public long getSizeAfterDeployment()
        {
            return sizeAfterDeployment;
        }

        public boolean allThin()
        {
            return allThin;
        }

        @Override
        public String toString()
        {
            return "Candidate [storPoolName=" + storPoolName + ", nodes=" + nodes +
                ", sizeAfterDeployment=" + sizeAfterDeployment + "]";
        }
    }

    public static class AutoStorPoolSelectorConfig
    {
        private final int placeCount;
        private final List<String> replicasOnDifferentList;
        private final List<String> replicasOnSameList;
        private final String notPlaceWithRscRegex;
        private final List<String> notPlaceWithRscList;
        private final String storPoolNameStr;

        public AutoStorPoolSelectorConfig(
            int placeCountRef,
            List<String> replicasOnDifferentListRef,
            List<String> replicasOnSameListRef,
            String notPlaceWithRscRegexRef,
            List<String> notPlaceWithRscListRef,
            String storPoolNameStrRef
        )
        {
            placeCount = placeCountRef;
            replicasOnDifferentList = replicasOnDifferentListRef;
            replicasOnSameList = replicasOnSameListRef;
            notPlaceWithRscRegex = notPlaceWithRscRegexRef;
            notPlaceWithRscList = notPlaceWithRscListRef;
            storPoolNameStr = storPoolNameStrRef;
        }

        public int getPlaceCount()
        {
            return placeCount;
        }

        public List<String> getReplicasOnDifferentList()
        {
            return replicasOnDifferentList;
        }

        public List<String> getReplicasOnSameList()
        {
            return replicasOnSameList;
        }

        public String getNotPlaceWithRscRegex()
        {
            return notPlaceWithRscRegex;
        }

        public List<String> getNotPlaceWithRscList()
        {
            return notPlaceWithRscList;
        }

        public String getStorPoolNameStr()
        {
            return storPoolNameStr;
        }
    }

    @FunctionalInterface
    public interface NodeSelectionStrategy
    {
        /**
         * @return A comparator for nodes where the preferred node has the greater value as defined by
         * {@link Comparator#compare(Object, Object)}.
         */
        Comparator<Node> makeComparator(StorPoolName storPoolName, AccessContext accCtx);
    }
}
