package com.linbit.linstor.api.protobuf.controller;

import com.linbit.ImplementationError;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.linstor.api.ApiCallReactive;
import com.linbit.linstor.api.protobuf.ProtobufApiCall;
import com.linbit.linstor.core.LinStor;
import com.linbit.linstor.core.apicallhandler.controller.CtrlFullSyncApiCallHandler;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.proto.javainternal.MsgIntAuthSuccessOuterClass.MsgIntAuthSuccess;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.Identity;
import com.linbit.linstor.security.Privilege;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;

@ProtobufApiCall(
    name = InternalApiConsts.API_AUTH_ACCEPT,
    description = "Called by the satellite to indicate that controller authentication succeeded",
    requiresAuth = false
)
public class IntAuthAccept implements ApiCallReactive
{
    private final ErrorReporter errorReporter;
    private final CtrlFullSyncApiCallHandler ctrlFullSyncApiCallHandler;
    private final Peer peer;
    private final AccessContext sysCtx;

    @Inject
    public IntAuthAccept(
        ErrorReporter errorReporterRef,
        CtrlFullSyncApiCallHandler ctrlFullSyncApiCallHandlerRef,
        Peer peerRef,
        @SystemContext AccessContext sysCtxRef
    )
    {
        errorReporter = errorReporterRef;
        ctrlFullSyncApiCallHandler = ctrlFullSyncApiCallHandlerRef;
        peer = peerRef;
        sysCtx = sysCtxRef;
    }

    @Override
    public Flux<byte[]> executeReactive(InputStream msgDataIn)
        throws IOException
    {
        Flux<?> flux;
        MsgIntAuthSuccess msgIntAuthSuccess = MsgIntAuthSuccess.parseDelimitedFrom(msgDataIn);
        long expectedFullSyncId = msgIntAuthSuccess.getExpectedFullSyncId();

        if (LinStor.VERSION_INFO_PROVIDER.equalsVersion(
            msgIntAuthSuccess.getVersionMajor(),
            msgIntAuthSuccess.getVersionMinor(),
            msgIntAuthSuccess.getVersionPatch()))
        {
            peer.setAuthenticated(true);
            peer.setConnectionStatus(Peer.ConnectionStatus.CONNECTED);

            // Set the satellite's access context
            // Certain APIs called by the satellite are executed with a privileged access context by the controller,
            // while the access context of the peer connection itself remains unprivileged
            AccessContext curCtx = peer.getAccessContext();
            AccessContext privCtx = sysCtx.clone();
            try
            {
                privCtx.getEffectivePrivs().enablePrivileges(Privilege.PRIV_SYS_ALL);
                // FIXME In the absence of any means of identification, assume the system identity for the peer.
                // Set the SYSTEM identity on the Satellite's access context
                AccessContext newCtx = privCtx.impersonate(Identity.SYSTEM_ID, curCtx.subjectRole, curCtx.subjectDomain);
                // Disable all privileges on the Satellite's access context permanently
                newCtx.getLimitPrivs().disablePrivileges(Privilege.PRIV_SYS_ALL);
                peer.setAccessContext(privCtx, newCtx);
            }
            catch (AccessDeniedException accExc)
            {
                errorReporter.reportError(
                    Level.ERROR,
                    new ImplementationError(
                        "Creation of an access context for a Satellite by the " + privCtx.subjectRole.name.displayValue +
                            " role failed",
                        accExc
                    )
                );
            }
            errorReporter.logDebug("Satellite '" + peer.getNode().getName() + "' authenticated");

            flux = ctrlFullSyncApiCallHandler.sendFullSync(peer, expectedFullSyncId);
        }
        else
        {
            peer.setConnectionStatus(Peer.ConnectionStatus.VERSION_MISMATCH);
            errorReporter.logError(
                String.format(
                    "Satellite '%s' version mismatch(v%d.%d.%d).",
                    peer.getNode().getName(),
                    msgIntAuthSuccess.getVersionMajor(),
                    msgIntAuthSuccess.getVersionMinor(),
                    msgIntAuthSuccess.getVersionPatch()
                )
            );
            peer.closeConnection();
            flux = Flux.empty();
        }

        return flux.thenMany(Flux.<byte[]>empty());
    }
}