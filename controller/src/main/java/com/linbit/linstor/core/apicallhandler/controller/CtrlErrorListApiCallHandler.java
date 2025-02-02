package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.core.LinStor;
import com.linbit.linstor.core.apicallhandler.ScopeRunner;
import com.linbit.linstor.core.apicallhandler.response.ApiAccessDeniedException;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.core.repository.NodeRepository;
import com.linbit.linstor.logging.ErrorReport;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.logging.LinstorFile;
import com.linbit.linstor.logging.StdErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.netcom.PeerNotConnectedException;
import com.linbit.linstor.proto.responses.MsgErrorReportOuterClass;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.locks.LockGuardFactory;
import com.linbit.locks.LockGuardFactory.LockObj;
import com.linbit.locks.LockGuardFactory.LockType;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import reactor.core.publisher.Flux;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Singleton
public class CtrlErrorListApiCallHandler
{
    private final ScopeRunner scopeRunner;
    private final ErrorReporter errorReporter;
    private final NodeRepository nodeRepository;
    private final CtrlStltSerializer stltComSerializer;
    private final Provider<AccessContext> peerAccCtx;
    private final LockGuardFactory lockGuardFactory;

    @Inject
    public CtrlErrorListApiCallHandler(
        ErrorReporter errorReporterRef,
        NodeRepository nodeRepositoryRef,
        CtrlStltSerializer clientComSerializerRef,
        @PeerContext Provider<AccessContext> peerAccCtxRef,
        ScopeRunner scopeRunnerRef,
        LockGuardFactory lockGuardFactoryRef
    )
        {
        errorReporter = errorReporterRef;
        nodeRepository = nodeRepositoryRef;
        stltComSerializer = clientComSerializerRef;
        peerAccCtx = peerAccCtxRef;
        scopeRunner = scopeRunnerRef;
        lockGuardFactory = lockGuardFactoryRef;
    }

    public Flux<List<ErrorReport>> listErrorReports(
        final Set<String> nodes,
        boolean withContent,
        @Nullable final Date since,
        @Nullable final Date to,
        final Set<String> ids
    )
    {
        return scopeRunner
            .fluxInTransactionlessScope(
                "Collect error reports",
                lockGuardFactory.buildDeferred(LockType.READ, LockObj.NODES_MAP),
                () -> assembleRequests(nodes, withContent, since, to, ids)
            )
            .collectList()
            .flatMapMany(errorReportAnswers ->
                scopeRunner.fluxInTransactionlessScope(
                    "Assemble error report list",
                    lockGuardFactory.buildDeferred(LockType.READ, LockObj.NODES_MAP),
                    () -> Flux.just(assembleList(nodes, withContent, since, to, ids, errorReportAnswers))
                )
            );
    }

    private Flux<Tuple2<NodeName, ByteArrayInputStream>> assembleRequests(
        Set<String> nodesToRequest,
        boolean withContent,
        @Nullable final Date since,
        @Nullable final Date to,
        final Set<String> ids)
        throws AccessDeniedException
    {
        Stream<Node> nodeStream = nodeRepository.getMapForView(peerAccCtx.get()).values().stream()
            .filter(node -> nodesToRequest.isEmpty() ||
                nodesToRequest.stream().anyMatch(node.getName().getDisplayName()::equalsIgnoreCase));

        List<Tuple2<NodeName, Flux<ByteArrayInputStream>>> nameAndRequests = nodeStream
            .map(node -> Tuples.of(node.getName(), prepareErrRequestApi(node, withContent, since, to, ids)))
            .collect(Collectors.toList());

        return Flux
            .fromIterable(nameAndRequests)
            .flatMap(nameAndRequest -> nameAndRequest.getT2()
                .map(byteStream -> Tuples.of(nameAndRequest.getT1(), byteStream)));
    }

    private Flux<ByteArrayInputStream> prepareErrRequestApi(
        final Node node,
        boolean withContent,
        @Nullable final Date since,
        @Nullable final Date to,
        final Set<String> ids)
    {
        Peer peer = getPeer(node);
        Flux<ByteArrayInputStream> fluxReturn = Flux.empty();
        if (peer != null)
        {
            byte[] msg = stltComSerializer.headerlessBuilder()
                .requestErrorReports(new HashSet<>(), withContent, since, to, ids).build();
            fluxReturn = peer.apiCall(ApiConsts.API_REQ_ERROR_REPORTS, msg)
                .onErrorResume(PeerNotConnectedException.class, ignored -> Flux.empty());
        }
        return fluxReturn;
    }

    private Peer getPeer(Node node)
    {
        Peer peer;
        try
        {
            peer = node.getPeer(peerAccCtx.get());
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw new ApiAccessDeniedException(
                accDeniedExc,
                "access peer for node '" + node.getName().displayValue + "'",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
        return peer;
    }

    private List<ErrorReport> assembleList(
        Set<String> nodesToRequest,
        boolean withContent,
        @Nullable final Date since,
        @Nullable final Date to,
        final Set<String> ids,
        List<Tuple2<NodeName, ByteArrayInputStream>> errorReportsAnswers)
        throws IOException
    {
        List<ErrorReport> errorReports = new ArrayList<>();

        // Controller error reports
        if (nodesToRequest.isEmpty() || nodesToRequest.stream().anyMatch(LinStor.CONTROLLER_MODULE::equalsIgnoreCase))
        {
            errorReports.addAll(errorReporter.listReports(
                withContent,
                since,
                to,
                ids
            ));
        }

        // Returned satellite error reports
        for (Tuple2<NodeName, ByteArrayInputStream> errorReportAnswer : errorReportsAnswers)
        {
            // NodeName nodeName = errorReportAnswer.getT1();
            ByteArrayInputStream errorReportMsgDataIn = errorReportAnswer.getT2();

            errorReports.addAll(deserializeErrorReports(errorReportMsgDataIn));
        }

        errorReports.sort(LinstorFile::compareTo);
        return errorReports;
    }

    // TODO? hide deserialization in interface?
    private static Set<ErrorReport> deserializeErrorReports(InputStream msgDataIn)
        throws IOException
    {
        MsgErrorReportOuterClass.MsgErrorReport msgErrorReport;
        Set<ErrorReport> errorReports = new TreeSet<>();
        while ((msgErrorReport = MsgErrorReportOuterClass.MsgErrorReport.parseDelimitedFrom(msgDataIn)) != null)
        {
            errorReports.add(new ErrorReport(
                msgErrorReport.getNodeNames(),
                Node.Type.getByValue(msgErrorReport.getModule()),
                msgErrorReport.getFilename(),
                msgErrorReport.getVersion(),
                msgErrorReport.getPeer(),
                msgErrorReport.getException(),
                msgErrorReport.getExceptionMessage(),
                msgErrorReport.getOriginFile(),
                msgErrorReport.getOriginMethod(),
                msgErrorReport.getOriginLine(),
                new Date(msgErrorReport.getErrorTime()),
                msgErrorReport.getText())
            );
        }
        return errorReports;
    }
}
