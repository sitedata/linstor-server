package com.linbit.linstor.netcom;

import com.linbit.ServiceName;

import javax.inject.Singleton;
import java.util.Map;
import java.util.TreeMap;

@Singleton
public class NetComContainerImpl implements NetComContainer
{
    // Map of network communications connectors
    private final Map<ServiceName, TcpConnector> netComConnectors;

    public NetComContainerImpl()
    {
        netComConnectors = new TreeMap<>();
    }

    @Override
    public TcpConnector getNetComConnector(ServiceName conSvcName)
    {
        return netComConnectors.get(conSvcName);
    }

    @Override
    public void removeNetComConnector(ServiceName conSvnName)
    {
        netComConnectors.remove(conSvnName);
    }

    @Override
    public void putNetComContainer(ServiceName conSvcName, TcpConnector netComSvc)
    {
        netComConnectors.put(conSvcName, netComSvc);
    }
}
