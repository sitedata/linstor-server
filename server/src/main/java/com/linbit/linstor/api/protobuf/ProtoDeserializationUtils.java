package com.linbit.linstor.api.protobuf;

import com.linbit.ImplementationError;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.pojo.StorPoolPojo;
import com.linbit.linstor.core.apis.StorPoolApi;
import com.linbit.linstor.core.objects.VolumeGroup.VlmGrpApi;
import com.linbit.linstor.proto.common.ApiCallResponseOuterClass;
import com.linbit.linstor.proto.common.LayerTypeOuterClass.LayerType;
import com.linbit.linstor.proto.common.ProviderTypeOuterClass.ProviderType;
import com.linbit.linstor.proto.common.StorPoolOuterClass.StorPool;
import com.linbit.linstor.api.pojo.AutoSelectFilterPojo;
import com.linbit.linstor.api.pojo.RscGrpPojo;
import com.linbit.linstor.api.pojo.VlmGrpPojo;
import com.linbit.linstor.proto.common.RscGrpOuterClass.RscGrp;
import com.linbit.linstor.proto.common.VlmGrpOuterClass.VlmGrp;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.storage.kinds.DeviceProviderKind;
import com.linbit.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;

public class ProtoDeserializationUtils
{
    public static ApiCallRc.RcEntry parseApiCallRc(
        ApiCallResponseOuterClass.ApiCallResponse apiCallResponse,
        String messagePrefix
    )
    {
        ApiCallRcImpl.EntryBuilder entryBuilder = ApiCallRcImpl
            .entryBuilder(
                apiCallResponse.getRetCode(),
                messagePrefix + apiCallResponse.getMessage()
            );

        if (!StringUtils.isEmpty(apiCallResponse.getCause()))
        {
            entryBuilder.setCause(apiCallResponse.getCause());
        }
        if (!StringUtils.isEmpty(apiCallResponse.getCorrection()))
        {
            entryBuilder.setCorrection(apiCallResponse.getCorrection());
        }
        if (!StringUtils.isEmpty(apiCallResponse.getDetails()))
        {
            entryBuilder.setDetails(apiCallResponse.getDetails());
        }

        entryBuilder.putAllObjRefs(apiCallResponse.getObjRefsMap());

        entryBuilder.addAllErrorIds(apiCallResponse.getErrorReportIdsList());

        return entryBuilder.build();
    }

    public static ApiCallRc parseApiCallRcList(
        List<ApiCallResponseOuterClass.ApiCallResponse> apiCallRcs
    )
    {
        return new ApiCallRcImpl(apiCallRcs.stream()
            .map(apiCallResponse -> parseApiCallRc(apiCallResponse, ""))
            .collect(Collectors.toList()));
    }

    public static byte[] extractByteArray(ByteString protoBytes)
    {
        byte[] arr = new byte[protoBytes.size()];
        protoBytes.copyTo(arr, 0);
        return arr;
    }

    public static List<DeviceProviderKind> parseDeviceProviderKind(List<ProviderType> providerTypeList)
    {
        return parseDeviceProviderKind(providerTypeList, true);
    }

    public static List<DeviceProviderKind> parseDeviceProviderKind(
        List<ProviderType> providerTypeList,
        boolean throwIfUnknown
    )
    {
        List<DeviceProviderKind> providerKindList = new ArrayList<>();
        for (ProviderType providerType : providerTypeList)
        {
            providerKindList.add(parseDeviceProviderKind(providerType, throwIfUnknown));
        }
        return providerKindList;
    }

    public static DeviceProviderKind parseDeviceProviderKind(ProviderType providerKindRef)
    {
        return parseDeviceProviderKind(providerKindRef, true);
    }
    public static DeviceProviderKind parseDeviceProviderKind(ProviderType providerKindRef, boolean throwIfUnknown)
    {
        DeviceProviderKind kind = null;
        if (providerKindRef != null)
        {
            switch (providerKindRef)
            {
                case DISKLESS:
                    kind = DeviceProviderKind.DISKLESS;
                    break;
                case LVM:
                    kind = DeviceProviderKind.LVM;
                    break;
                case LVM_THIN:
                    kind = DeviceProviderKind.LVM_THIN;
                    break;
                case SWORDFISH_INITIATOR:
                    kind = DeviceProviderKind.SWORDFISH_INITIATOR;
                    break;
                case SWORDFISH_TARGET:
                    kind = DeviceProviderKind.SWORDFISH_TARGET;
                    break;
                case ZFS:
                    kind = DeviceProviderKind.ZFS;
                    break;
                case ZFS_THIN:
                    kind = DeviceProviderKind.ZFS_THIN;
                    break;
                case FILE:
                    kind = DeviceProviderKind.FILE;
                    break;
                case FILE_THIN:
                    kind = DeviceProviderKind.FILE_THIN;
                    break;
                case UNKNOWN_PROVIDER: // fall-through
                case UNRECOGNIZED: // fall-through
                default:
                    if (throwIfUnknown)
                    {
                        throw new ImplementationError("Unknown (proto) ProviderType: " + providerKindRef);
                    }
            }
        }
        return kind;
    }

    public static List<DeviceLayerKind> parseDeviceLayerKindList(List<LayerType> layerTypeList)
    {
        return parseDeviceLayerKindList(layerTypeList, true);
    }

    public static List<DeviceLayerKind> parseDeviceLayerKindList(List<LayerType> layerTypeList, boolean throwIfUnknown)
    {
        List<DeviceLayerKind> devLayerKindList = new ArrayList<>();
        for (LayerType layerType : layerTypeList)
        {
            devLayerKindList.add(parseDeviceLayerKind(layerType, throwIfUnknown));
        }
        return devLayerKindList;
    }

    public static DeviceLayerKind parseDeviceLayerKind(LayerType layerTypeRef)
    {
        return parseDeviceLayerKind(layerTypeRef, true);
    }

    public static DeviceLayerKind parseDeviceLayerKind(LayerType layerTypeRef, boolean throwIfUnknown)
    {
        DeviceLayerKind kind = null;
        switch (layerTypeRef)
        {
            case DRBD:
                kind = DeviceLayerKind.DRBD;
                break;
            case LUKS:
                kind = DeviceLayerKind.LUKS;
                break;
            case STORAGE:
                kind = DeviceLayerKind.STORAGE;
                break;
            case NVME:
                kind = DeviceLayerKind.NVME;
                break;
            case UNKNOWN_LAYER: // fall-through
            case UNRECOGNIZED: // fall-through
            default:
                if (throwIfUnknown)
                {
                    throw new ImplementationError("Unknown (proto) LayerType: " + layerTypeRef);
                }
        }
        return kind;
    }

    public static StorPoolApi parseStorPool(StorPool storPoolProto, long fullSyncId, long updateId)
    {
        return new StorPoolPojo(
            UUID.fromString(storPoolProto.getStorPoolUuid()),
            UUID.fromString(storPoolProto.getNodeUuid()),
            storPoolProto.getNodeName(),
            storPoolProto.getStorPoolName(),
            UUID.fromString(storPoolProto.getStorPoolDfnUuid()),
            parseDeviceProviderKind(storPoolProto.getProviderKind()),
            storPoolProto.getPropsMap(),
            storPoolProto.getStorPoolDfnPropsMap(),
            storPoolProto.getStaticTraitsMap(),
            fullSyncId,
            updateId,
            storPoolProto.getFreeSpaceMgrName(),
            Optional.ofNullable(
                storPoolProto.hasFreeSpace() && storPoolProto.getFreeSpace().hasFreeCapacity() ?
                    storPoolProto.getFreeSpace().getFreeCapacity() :
                    null
            ),
            Optional.ofNullable(
                storPoolProto.hasFreeSpace() && storPoolProto.getFreeSpace().hasTotalCapacity() ?
                    storPoolProto.getFreeSpace().getTotalCapacity() :
                    null
            ),
            null,
            storPoolProto.getSnapshotSupported()
        );
    }

    private ProtoDeserializationUtils()
    {
    }

    public static RscGrpPojo parseRscGrp(RscGrp rscGrpProto)
    {
        return new RscGrpPojo(
            UUID.fromString(rscGrpProto.getUuid()),
            rscGrpProto.getName(),
            rscGrpProto.getDescription(),
            rscGrpProto.getRscDfnPropsMap(),
            parseVlmGrpList(rscGrpProto.getVlmGrpList()),
            // satellite does not need the autoSelectFilter anyways
            new AutoSelectFilterPojo(null, null, null, null, null, null, null, null, null)
        );
    }

    public static List<VlmGrpApi> parseVlmGrpList(List<VlmGrp> vlmGrpProtoList)
    {
        List<VlmGrpApi> ret = new ArrayList<>();
        for (VlmGrp vlmGrpProto : vlmGrpProtoList)
        {
            ret.add(parseVlmGrp(vlmGrpProto));
        }
        return ret;
    }

    public static VlmGrpPojo parseVlmGrp(VlmGrp vlmGrpProto)
    {
        return new VlmGrpPojo(
            UUID.fromString(vlmGrpProto.getUuid()),
            vlmGrpProto.getVlmNr(),
            vlmGrpProto.getVlmDfnPropsMap()
        );
    }
}
