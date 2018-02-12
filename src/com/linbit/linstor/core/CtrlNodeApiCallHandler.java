package com.linbit.linstor.core;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import com.linbit.ImplementationError;
import com.linbit.InvalidIpAddressException;
import com.linbit.InvalidNameException;
import com.linbit.ServiceName;
import com.linbit.TransactionMgr;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.LinStorDataAlreadyExistsException;
import com.linbit.linstor.LinStorException;
import com.linbit.linstor.LinStorRuntimeException;
import com.linbit.linstor.LsIpAddress;
import com.linbit.linstor.NetInterface;
import com.linbit.linstor.NetInterface.NetInterfaceApi;
import com.linbit.linstor.NetInterfaceData;
import com.linbit.linstor.NetInterfaceName;
import com.linbit.linstor.Node;
import com.linbit.linstor.Node.NodeFlag;
import com.linbit.linstor.Node.NodeType;
import com.linbit.linstor.NodeData;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.Resource;
import com.linbit.linstor.SatelliteConnection;
import com.linbit.linstor.SatelliteConnection.EncryptionType;
import com.linbit.linstor.SatelliteConnection.SatelliteConnectionApi;
import com.linbit.linstor.SatelliteConnectionData;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.TcpPortNumber;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.ApiCallRcImpl.ApiCallRcEntry;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.interfaces.serializer.CtrlClientSerializer;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.dbcp.DbConnectionPool;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.NetComContainer;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.netcom.TcpConnector;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.AccessType;
import com.linbit.linstor.security.ObjectProtection;
import com.linbit.linstor.security.SecurityModule;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

@Singleton
public class CtrlNodeApiCallHandler extends AbsApiCallHandler
{
    private final ThreadLocal<String> currentNodeName = new ThreadLocal<>();
    private final ThreadLocal<String> currentNodeType = new ThreadLocal<>();
    private final CtrlClientSerializer clientComSerializer;
    private final Props ctrlConf;
    private final CoreModule.NodesMap nodesMap;
    private final ObjectProtection nodesMapProt;
    private final SatelliteConnector satelliteConnector;
    private final NetComContainer netComContainer;

    @Inject
    public CtrlNodeApiCallHandler(
        ErrorReporter errorReporterRef,
        DbConnectionPool dbConnectionPoolRef,
        AccessContext apiCtxRef,
        CtrlStltSerializer interComSerializer,
        CtrlClientSerializer clientComSerializerRef,
        @Named(CoreModule.CONTROLLER_PROPS) Props ctrlConfRef,
        CoreModule.NodesMap nodesMapRef,
        @Named(SecurityModule.NODES_MAP_PROT) ObjectProtection nodesMapProtRef,
        SatelliteConnector satelliteConnectorRef,
        NetComContainer netComContainerRef
    )
    {
        super(errorReporterRef, dbConnectionPoolRef, apiCtxRef, ApiConsts.MASK_NODE, interComSerializer);
        clientComSerializer = clientComSerializerRef;
        ctrlConf = ctrlConfRef;
        nodesMap = nodesMapRef;
        nodesMapProt = nodesMapProtRef;
        satelliteConnector = satelliteConnectorRef;
        netComContainer = netComContainerRef;
    }

    /**
     * Attempts to create a node by the given parameters. <br />
     * <br />
     * In any case an {@link ApiCallRc} is returned. The list of {@link ApiCallRcEntry}s describe the success
     * or failure of the operation. <br />
     * <br />
     * All return codes from this method are masked with {@link ApiConsts#MASK_NODE} and
     * {@link ApiConsts#MASK_CRT}.<br />
     * <br />
     * Following return codes can be returned:
     * <ul>
     *  <li>
     *      {@link ApiConsts#FAIL_ACC_DENIED_NODE} when the current access context does have enough privileges to
     *      change any nodes at all (controller.nodesMapLockProt)
     *  </li>
     *  <li>{@link ApiConsts#FAIL_MISSING_NETCOM} when the list of network interface apis is empty</li>
     *  <li>
     *      {@link ApiConsts#FAIL_INVLD_NET_NAME} when the list of network interface apis contains an invalid
     *      {@link NetInterfaceName}
     *  </li>
     *  <li>
     *      {@link ApiConsts#FAIL_INVLD_NET_ADDR} when the list of network interface apis contains an invalid
     *      {@link LsIpAddress}
     *  </li>
     *  <li>{@link ApiConsts#FAIL_MISSING_STLT_CONN} when the list of satellite connection apis is empty</li>
     *  <li>{@link ApiConsts#FAIL_INVLD_NODE_NAME} when the {@link NodeName} is invalid</li>
     *  <li>{@link ApiConsts#FAIL_INVLD_NODE_TYPE} when the {@link NodeType} is invalid</li>
     *  <li>{@link ApiConsts#CREATED} when the node was created successfully </li>
     * </ul>
     *
     * @param accCtx
     * @param client
     * @param nodeNameStr
     * @param nodeTypeStr
     * @param netIfs
     * @param satelliteConnectionApis
     * @param propsMap
     * @return
     */
    public ApiCallRc createNode(
        AccessContext accCtx,
        Peer client,
        String nodeNameStr,
        String nodeTypeStr,
        List<NetInterfaceApi> netIfs,
        List<SatelliteConnectionApi> satelliteConnectionApis,
        Map<String, String> propsMap
    )
    {
        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();

        try (
            AbsApiCallHandler basicallyThis = setContext(
                accCtx,
                client,
                ApiCallType.CREATE,
                apiCallRc,
                null, // create new TransactionMgr
                nodeNameStr,
                nodeTypeStr
            )
        )
        {
            requireNodesMapChangeAccess();
            NodeName nodeName = asNodeName(nodeNameStr);

            NodeType type = asNodeType(nodeTypeStr);

            Node node = createNode(nodeName, type);

            Props nodeProps = getProps(node);
            nodeProps.map().putAll(propsMap);

            if (netIfs.isEmpty())
            {
                // TODO for auxiliary nodes maybe no netif required?
                reportMissingNetInterfaces();
            }
            else
            if (satelliteConnectionApis.isEmpty())
            {
                // TODO for auxiliary nodes maybe no stltConn required?
                reportMissingSatelliteConnection();
            }
            else
            {
                Map<String, NetInterface> netIfMap = new TreeMap<>();

                for (NetInterfaceApi netIfApi : netIfs)
                {
                    NetInterfaceData netIf = createNetInterface(
                        node,
                        asNetInterfaceName(netIfApi.getName()),
                        asLsIpAddress(netIfApi.getAddress())
                    );
                    netIfMap.put(netIfApi.getName(), netIf);
                }

                SatelliteConnectionApi stltConnApi = satelliteConnectionApis.iterator().next();

                createSatelliteConnection(
                    node,
                    stltConnApi
                );

                commit();
                nodesMap.put(nodeName, node);

                reportSuccess(node.getUuid());

                if (type.equals(NodeType.SATELLITE) || type.equals(NodeType.COMBINED))
                {
                    startConnecting(node, accCtx);
                }
            }
        }
        catch (ApiCallHandlerFailedException ignore)
        {
            // a report and a corresponding api-response already created. nothing to do here
        }
        catch (Exception | ImplementationError exc)
        {
            reportStatic(
                exc,
                ApiCallType.CREATE,
                getObjectDescriptionInline(nodeNameStr),
                getObjRefs(nodeNameStr),
                getVariables(nodeNameStr),
                apiCallRc,
                accCtx,
                client
            );
        }
        return apiCallRc;
    }

    public ApiCallRc modifyNode(
        AccessContext accCtx,
        Peer client,
        UUID nodeUuid,
        String nodeNameStr,
        String nodeTypeStr,
        Map<String, String> overrideProps,
        Set<String> deletePropKeys
    )
    {
        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();
        try (
            AbsApiCallHandler basicallyThis = setContext(
                accCtx,
                client,
                ApiCallType.MODIFY,
                apiCallRc,
                null, // create transMgr
                nodeNameStr,
                nodeTypeStr
            )
        )
        {
            requireNodesMapChangeAccess();
            NodeName nodeName = asNodeName(nodeNameStr);
            NodeData node = loadNode(nodeName, true);
            if (nodeUuid != null && !nodeUuid.equals(node.getUuid()))
            {
                addAnswer(
                    "UUID-check failed",
                    ApiConsts.FAIL_UUID_NODE
                );
                throw new ApiCallHandlerFailedException();
            }
            if (nodeTypeStr != null)
            {
                setNodeType(node, nodeTypeStr);
            }
            Map<String, String> nodeProps = getProps(node).map();
            nodeProps.putAll(overrideProps);
            for (String key : deletePropKeys)
            {
                nodeProps.remove(key);
            }

            commit();

            updateSatellites(node);
            reportSuccess(node.getUuid());
        }
        catch (ApiCallHandlerFailedException ignore)
        {
            // a report and a corresponding api-response already created. nothing to do here
        }
        catch (Exception | ImplementationError exc)
        {
            reportStatic(
                exc,
                ApiCallType.MODIFY,
                getObjectDescriptionInline(nodeNameStr),
                getObjRefs(nodeNameStr),
                getVariables(nodeNameStr),
                apiCallRc,
                accCtx,
                client
            );
        }

        return apiCallRc;
    }

    ApiCallRc deleteNode(AccessContext accCtx, Peer client, String nodeNameStr)
    {
        ApiCallRcImpl apiCallRc = new ApiCallRcImpl();

        try (
            AbsApiCallHandler basicallythis = setContext(
                accCtx,
                client,
                ApiCallType.DELETE,
                apiCallRc,
                null,
                nodeNameStr,
                null
            )
        )
        {
            requireNodesMapChangeAccess();
            NodeName nodeName = asNodeName(nodeNameStr);
            NodeData nodeData = loadNode(nodeName, true);
            if (nodeData == null)
            {
                addAnswer(
                    "Deletion of node '" + currentNodeName.get() + "' had no effect.",
                    "Node '" + currentNodeName.get() + "' does not exist.",
                    null,
                    null,
                    ApiConsts.WARN_NOT_FOUND
                );
                throw new ApiCallHandlerFailedException();
            }
            else
            {
                boolean success = true;
                boolean hasRsc = false;

                Iterator<Resource> rscIterator = getRscIterator(nodeData);
                while (rscIterator.hasNext())
                {
                    hasRsc = true;
                    Resource rsc = rscIterator.next();
                    markDeleted(rsc);
                }
                if (!hasRsc)
                {
                    // If the node has no resources, then there should not be any volumes referenced
                    // by the storage pool -- double check
                    Iterator<StorPool> storPoolIterator = getStorPoolIterator(nodeData);
                    while (storPoolIterator.hasNext())
                    {
                        StorPool storPool = storPoolIterator.next();
                        if (!hasVolumes(storPool))
                        {
                            delete(storPool);
                        }
                        else
                        {
                            success = false;
                            addAnswer(
                                String.format(
                                    "Deletion of node '%s' failed because the storage pool '%s' references volumes " +
                                    "on this node, although the node does not reference any resources",
                                    nodeNameStr,
                                    storPool.getName().displayValue
                                ),
                                ApiConsts.FAIL_EXISTS_VLM
                            );
                        }
                    }
                }

                if (success)
                {
                    String successMessage = getObjectDescriptionInlineFirstLetterCaps();
                    UUID nodeUuid = nodeData.getUuid(); // store node uuid to avoid deleted node acess
                    if (hasRsc)
                    {
                        markDeleted(nodeData);
                        successMessage += " marked for deletion.";
                    }
                    else
                    {
                        delete(nodeData);
                        successMessage += " deleted.";
                    }

                    commit();

                    if (!hasRsc)
                    {
                        nodesMap.remove(nodeName);
                    }
                    else
                    {
                        updateSatellites(nodeData);
                    }

                    reportSuccess(
                        successMessage,
                        getObjectDescriptionInlineFirstLetterCaps() + " UUID is: " + nodeUuid.toString()
                    );

                    // TODO: tell satellites to remove all the corresponding resources and storPools
                    // TODO: if satellites finished, cleanup the storPools and then remove the node from DB
                }
            }
        }
        catch (ApiCallHandlerFailedException ignore)
        {
            // a report and a corresponding api-response already created. nothing to do here
        }
        catch (Exception | ImplementationError exc)
        {
            reportStatic(
                exc,
                ApiCallType.DELETE,
                getObjectDescriptionInline(nodeNameStr),
                getObjRefs(nodeNameStr),
                getVariables(nodeNameStr),
                apiCallRc,
                accCtx,
                client
            );
        }

        return apiCallRc;
    }

    byte[] listNodes(int msgId, AccessContext accCtx)
    {
        ArrayList<Node.NodeApi> nodes = new ArrayList<>();
        try
        {
            nodesMapProt.requireAccess(accCtx, AccessType.VIEW); // accDeniedExc1
            for (Node node : nodesMap.values())
            {
                try
                {
                    nodes.add(node.getApiData(accCtx, null, null));
                    // fullSyncId and updateId null, as they are not going to be serialized by
                    // .nodeList anyways
                }
                catch (AccessDeniedException accDeniedExc)
                {
                    // don't add nodes we have not access
                }
            }
        }
        catch (AccessDeniedException accDeniedExc)
        {
            // for now return an empty list.
        }

        return clientComSerializer.builder(ApiConsts.API_LST_NODE, msgId).nodeList(nodes).build();
    }

    void respondNode(int msgId, Peer satellite, UUID nodeUuid, String nodeNameStr)
    {
        try
        {
            NodeName nodeName = new NodeName(nodeNameStr);

            Node node = nodesMap.get(nodeName);
            if (node != null)
            {
                if (node.getUuid().equals(nodeUuid))
                {
                    Collection<Node> otherNodes = new TreeSet<>();
                    // otherNodes can be filled with all nodes (except the current 'node')
                    // related to the satellite. The serializer only needs the other nodes for
                    // the nodeConnections.
                    Iterator<Resource> rscIterator = satellite.getNode().iterateResources(apiCtx);
                    while (rscIterator.hasNext())
                    {
                        Resource rsc = rscIterator.next();
                        Iterator<Resource> otherRscIterator = rsc.getDefinition().iterateResource(apiCtx);
                        while (otherRscIterator.hasNext())
                        {
                            Resource otherRsc = otherRscIterator.next();
                            if (otherRsc != rsc)
                            {
                                otherNodes.add(otherRsc.getAssignedNode());
                            }
                        }
                    }
                    long fullSyncTimestamp = satellite.getFullSyncId();
                    long serializerId = satellite.getNextSerializerId();
                    satellite.sendMessage(
                        internalComSerializer
                            .builder(InternalApiConsts.API_APPLY_NODE, msgId)
                            .nodeData(node, otherNodes, fullSyncTimestamp, serializerId)
                            .build()
                    );
                }
                else
                {
                    errorReporter.reportError(
                        new ImplementationError(
                            "Satellite '" + satellite.getId() + "' requested a node with an outdated " +
                            "UUID. Current UUID: " + node.getUuid() + ", satellites outdated UUID: " +
                            nodeUuid,
                            null
                        )
                    );
                }
            }
            else
            {
                satellite.sendMessage(
                    internalComSerializer.builder(InternalApiConsts.API_APPLY_NODE_DELETED, msgId)
                        .deletedNodeData(nodeNameStr)
                        .build()
                );
            }
        }
        catch (Exception exc)
        {
            errorReporter.reportError(
                new ImplementationError(exc)
            );
        }
    }

    public void startConnecting(
        Node node,
        AccessContext accCtx
    )
    {
        try
        {
            SatelliteConnection satelliteConnection = node.getSatelliteConnection(accCtx);
            if (satelliteConnection != null)
            {
                EncryptionType type = satelliteConnection.getEncryptionType();
                String serviceType;
                switch (type)
                {
                    case PLAIN:
                        serviceType = Controller.PROPSCON_KEY_DEFAULT_PLAIN_CON_SVC;
                        break;
                    case SSL:
                        serviceType = Controller.PROPSCON_KEY_DEFAULT_SSL_CON_SVC;
                        break;
                    default:
                        throw new ImplementationError(
                            "Unhandled default case for EncryptionType",
                            null
                        );
                }
                ServiceName dfltConSvcName;
                try
                {
                    dfltConSvcName = new ServiceName(
                        ctrlConf.getProp(serviceType)
                    );
                }
                catch (InvalidNameException invalidNameExc)
                {
                    throw new LinStorRuntimeException(
                        "The ServiceName of the default TCP connector is not valid",
                        invalidNameExc
                    );
                }
                TcpConnector tcpConnector = netComContainer.getNetComConnector(dfltConSvcName);

                if (tcpConnector != null)
                {
                    satelliteConnector.connectSatellite(
                        new InetSocketAddress(
                            satelliteConnection.getNetInterface().getAddress(accCtx).getAddress(),
                            satelliteConnection.getPort().value
                        ),
                        tcpConnector,
                        node
                    );
                }
                else
                {
                    throw new LinStorRuntimeException(
                        "Attempt to establish a " + type + " connection without a proper connector defined"
                    );
                }
            }
        }
        catch (AccessDeniedException | InvalidKeyException exc)
        {
            throw new LinStorRuntimeException(
                "Access to an object protected by access controls was revoked while a " +
                "controller<->satellite connect operation was in progress.",
                exc
            );
        }
    }

    private void requireNodesMapChangeAccess() throws ApiCallHandlerFailedException
    {
        try
        {
            nodesMapProt.requireAccess(
                currentAccCtx.get(),
                AccessType.CHANGE
            );
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                getAction("create", "modify", "delete") + " node entries",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
    }

    private NodeType asNodeType(String nodeTypeStr) throws ApiCallHandlerFailedException
    {
        NodeType nodeType;
        try
        {
            nodeType = NodeType.valueOfIgnoreCase(nodeTypeStr, NodeType.SATELLITE);
        }
        catch (IllegalArgumentException illegalArgExc)
        {
            throw asExc(
                illegalArgExc,
                "The specified node type '" + nodeTypeStr + "' is invalid.",
                null, // cause
                null, // details
                "Valid node types are:\n" +
                NodeType.CONTROLLER.name() + "\n" +
                NodeType.SATELLITE.name() + "\n" +
                NodeType.COMBINED.name() + "\n" +
                NodeType.AUXILIARY.name() + "\n", // correction
                ApiConsts.FAIL_INVLD_NODE_TYPE
            );
        }
        return nodeType;
    }

    private NodeData createNode(NodeName nodeName, NodeType type)
        throws ApiCallHandlerFailedException
    {
        NodeData node;
        try
        {
            node = NodeData.getInstance(
                currentAccCtx.get(),
                nodeName,
                type,
                new NodeFlag[0],
                currentTransMgr.get(),
                true,
                true
            );
        }
        catch (AccessDeniedException accDeniedExc)
        {
            // accDeniedExc during creation means that an objectProtection already exists
            // and gives no permission to the currentAccCtx to access it.
            // This means we have an existing objProt without corresponding Node --> exception
            throw asExc(
                new LinStorException(
                    "An accessDeniedException occured during creation of a node. That means the " +
                        "ObjectProtection (of the non-existing Node) denied access to the node. " +
                        "It is possible that someone has modified the database accordingly. Please " +
                        "file a bug report otherwise.",
                    accDeniedExc
                ),
                "ObjProt of non-existing Node denies access of creating the Node in question.",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
        catch (LinStorDataAlreadyExistsException dataAlreadyExistsExc)
        {
            throw asExc(
                dataAlreadyExistsExc,
                "Creation of node '" + nodeName.displayValue + "' failed.",
                "A node with the specified name '" + nodeName.displayValue + "' already exists.",
                null,
                "- Specify another name for the new node\n" +
                "or\n" +
                "- Delete the existing node before creating a new node with the same name",
                ApiConsts.FAIL_EXISTS_NODE
            );
        }
        catch (SQLException sqlExc)
        {
            throw handleSqlExc(sqlExc);
        }
        return node;
    }

    private NetInterfaceName asNetInterfaceName(String netIfNameStr)
        throws ApiCallHandlerFailedException
    {
        NetInterfaceName netInterfaceName;
        try
        {
            netInterfaceName = new NetInterfaceName(netIfNameStr);
        }
        catch (InvalidNameException invalidNameExc)
        {
            throw asExc(
                invalidNameExc,
                "The specified net interface name '" + netIfNameStr + "' is invalid.",
                ApiConsts.FAIL_INVLD_NET_NAME
            );
        }
        return netInterfaceName;
    }

    private LsIpAddress asLsIpAddress(String ipAddrStr)
        throws ApiCallHandlerFailedException
    {
        if (ipAddrStr == null)
        {
            throw asExc(
                null,
                "Node creation failed.",
                "No IP address for the new node was specified",
                null,
                "At least one network interface with a valid IP address must be defined for the new node.",
                ApiConsts.FAIL_INVLD_NET_ADDR
            );
        }
        LsIpAddress lsIpAddress;
        try
        {
            lsIpAddress = new LsIpAddress(ipAddrStr);
        }
        catch (InvalidIpAddressException invalidIpExc)
        {
            throw asExc(
                invalidIpExc,
                "Node creation failed.",
                "The specified IP address is not valid",
                "The specified input '" + ipAddrStr + "' is not a valid IP address.",
                "Specify a valid IPv4 or IPv6 address.",
                ApiConsts.FAIL_INVLD_NET_ADDR
            );
        }
        return lsIpAddress;
    }

    private TcpPortNumber asTcpPortNumber(int port)
    {
        TcpPortNumber tcpPortNumber;
        try
        {
            tcpPortNumber = new TcpPortNumber(port);
        }
        catch (Exception exc)
        {
            throw asExc(
                exc,
                "The given portNumber '" + port + "' is invalid.",
                ApiConsts.FAIL_INVLD_NET_PORT
            );
        }
        return tcpPortNumber;
    }

    private EncryptionType asEncryptionType(String encryptionTypeStr)
    {
        EncryptionType encryptionType;
        try
        {
            encryptionType = EncryptionType.valueOfIgnoreCase(encryptionTypeStr);
        }
        catch (Exception exc)
        {
            throw asExc(
                exc,
                "The given encryption type '" + encryptionTypeStr + "' is invalid.",
                ApiConsts.FAIL_INVLD_NET_TYPE
            );
        }
        return encryptionType;
    }

    private void reportMissingNetInterfaces()
    {
        throw asExc(
            null,
            "Creation of node '" + currentNodeName.get() + "' failed.",
            "No network interfaces were given.",
            null,
            "At least one network interface has to be given and be marked to be used for " +
            "controller-satellite communitaction.",
            ApiConsts.FAIL_MISSING_NETCOM
        );
    }

    private void reportMissingSatelliteConnection()
    {
        throw asExc(
            null,
            "Creation of node '" + currentNodeName.get() + "' failed.",
            "No network interfaces was specified as satellite connection.",
            null,
            "At least one network interface has to be given and be marked to be used for " +
            "controller-satellite communitaction.",
            ApiConsts.FAIL_MISSING_STLT_CONN
        );
    }

    private NetInterfaceData createNetInterface(
        Node node,
        NetInterfaceName netName,
        LsIpAddress addr
    )
        throws ApiCallHandlerFailedException
    {
        NetInterfaceData netIf;
        try
        {
            netIf = NetInterfaceData.getInstance(
                currentAccCtx.get(),
                node,
                netName,
                addr,
                currentTransMgr.get(),
                true,   // persist node
                true    // throw LinStorDataAlreadyExistsException if needed
            );
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "create the netinterface '" + netName + "' on node '" + node.getName() + "'.",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
        catch (LinStorDataAlreadyExistsException dataAlreadyExistsExc)
        {
            throw asExc(
                dataAlreadyExistsExc,
                "Creation of node '" + node.getName() + "' failed.",
                "A duplicate network interface name was encountered during node creation.",
                "The network interface name '" + netName + "' was specified for more than one network interface.",
                "A name that is unique per node must be specified for each network interface.",
                ApiConsts.FAIL_EXISTS_NET_IF
            );
        }
        catch (SQLException sqlExc)
        {
            throw handleSqlExc(sqlExc);
        }
        return netIf;
    }

    private void createSatelliteConnection(
        Node node,
        SatelliteConnectionApi stltConnApi
    )
    {
        NetInterfaceName stltNetIfName = asNetInterfaceName(stltConnApi.getNetInterfaceName());
        TcpPortNumber stltNetIfPort = asTcpPortNumber(stltConnApi.getPort());
        EncryptionType stltNetIfEncryptionType = asEncryptionType(stltConnApi.getEncryptionType());

        NetInterface netIf = getNetInterface(node, stltNetIfName);

        try
        {

            SatelliteConnectionData.getInstance(
                currentAccCtx.get(),
                node,
                netIf,
                stltNetIfPort,
                stltNetIfEncryptionType,
                currentTransMgr.get(),
                true,
                true
            );
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "creating a satellite connection",
                ApiConsts.FAIL_ACC_DENIED_STLT_CONN
            );
        }
        catch (LinStorDataAlreadyExistsException alreadyExistsExc)
        {
            throw new ImplementationError(
                "New node already had an satellite connection",
                alreadyExistsExc
            );
        }
        catch (SQLException sqlExc)
        {
            throw asSqlExc(sqlExc, "creating a satellite connection");
        }
    }


    private NetInterface getNetInterface(Node node, NetInterfaceName niName)
    {
        NetInterface netInterface;
        try
        {
            netInterface = node.getNetInterface(currentAccCtx.get(), niName);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "creating a satellite connection",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
        return netInterface;
    }

    private Iterator<Resource> getRscIterator(NodeData nodeData) throws ApiCallHandlerFailedException
    {
        Iterator<Resource> iterator;
        try
        {
            iterator = nodeData.iterateResources(apiCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        return iterator;
    }

    private void markDeleted(Resource rsc) throws ApiCallHandlerFailedException
    {
        try
        {
            rsc.setConnection(currentTransMgr.get());
            rsc.markDeleted(apiCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        catch (SQLException sqlExc)
        {
            throw asSqlExc(
                sqlExc,
                "An SQLException occured while marking resource '" + rsc.getDefinition().getName().displayValue +
                "' on node '" + currentNodeName.get() + "' as deleted "
            );
        }
    }

    private Iterator<StorPool> getStorPoolIterator(NodeData node) throws ApiCallHandlerFailedException
    {
        Iterator<StorPool> iterateStorPools;
        try
        {
            iterateStorPools = node.iterateStorPools(apiCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        return iterateStorPools;
    }

    private boolean hasVolumes(StorPool storPool) throws ApiCallHandlerFailedException
    {
        boolean hasVolumes;
        try
        {
            hasVolumes = !storPool.getVolumes(apiCtx).isEmpty();
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        return hasVolumes;
    }

    private void delete(StorPool storPool) throws ApiCallHandlerFailedException
    {
        try
        {
            storPool.setConnection(currentTransMgr.get());
            storPool.delete(apiCtx);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asImplError(accDeniedExc);
        }
        catch (SQLException sqlExc)
        {
            throw handleSqlExc(sqlExc);
        }
    }

    private void markDeleted(NodeData node) throws ApiCallHandlerFailedException
    {
        try
        {
            node.markDeleted(currentAccCtx.get());
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "delete the node '" + currentNodeName.get() + "'.",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
        catch (SQLException sqlExc)
        {
            throw handleSqlExc(sqlExc);
        }
    }

    private void delete(NodeData node) throws ApiCallHandlerFailedException
    {
        try
        {
            node.delete(currentAccCtx.get());
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "delete the node '" + currentNodeName.get() + "'.",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
        catch (SQLException sqlExc)
        {
            throw handleSqlExc(sqlExc);
        }
    }

    private void setNodeType(NodeData node, String nodeTypeStr)
    {
        NodeType nodeType = asNodeType(nodeTypeStr);
        try
        {
            node.setNodeType(currentAccCtx.get(), nodeType);
        }
        catch (AccessDeniedException accDeniedExc)
        {
            throw asAccDeniedExc(
                accDeniedExc,
                "update the node type",
                ApiConsts.FAIL_ACC_DENIED_NODE
            );
        }
        catch (SQLException sqlExc)
        {
            throw handleSqlExc(sqlExc);
        }
    }

    private ApiCallHandlerFailedException handleSqlExc(SQLException sqlExc)
    {
        throw asSqlExc(
            sqlExc,
            getAction(
                "creating node '" + currentNodeName.get() + "'",
                "deleting node '" + currentNodeName.get() + "'",
                "modifying node '" + currentNodeName.get() + "'"
            )
        );
    }

    private AbsApiCallHandler setContext(
        AccessContext accCtx,
        Peer client,
        ApiCallType apiCallType,
        ApiCallRcImpl apiCallRc,
        TransactionMgr transMgr,
        String nodeNameStr,
        String nodeTypeStr
    )
    {
        super.setContext(
            accCtx,
            client,
            apiCallType,
            apiCallRc,
            transMgr,
            getObjRefs(nodeNameStr),
            getVariables(nodeNameStr)
        );
        currentNodeName.set(nodeNameStr);
        currentNodeType.set(nodeTypeStr);
        return this;
    }

    private Map<String, String> getObjRefs(String nodeNameStr)
    {
        Map<String, String> map = new TreeMap<>();
        map.put(ApiConsts.KEY_NODE, nodeNameStr);
        return map;
    }

    private Map<String, String> getVariables(String nodeNameStr)
    {
        Map<String, String> map = new TreeMap<>();
        map.put(ApiConsts.KEY_NODE_NAME, nodeNameStr);
        return map;
    }

    @Override
    protected String getObjectDescription()
    {
        return "Node: " + currentNodeName.get();
    }

    @Override
    protected String getObjectDescriptionInline()
    {
        return getObjectDescriptionInline(currentNodeName.get());
    }

    static String getObjectDescriptionInline(String nodeNameStr)
    {
        return "node '" + nodeNameStr + "'";
    }


}
