package com.linbit.linstor.layer.storage.lvm.utils;

import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.linstor.layer.storage.utils.Commands;
import com.linbit.linstor.layer.storage.utils.Commands.RetryHandler;
import com.linbit.linstor.layer.storage.utils.RetryIfDeviceBusy;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.kinds.RaidLevel;

import static com.linbit.linstor.layer.storage.utils.Commands.genericExecutor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class LvmCommands
{
    public static final int LVS_COL_IDENTIFIER = 0;
    public static final int LVS_COL_PATH = 1;
    public static final int LVS_COL_SIZE = 2;
    public static final int LVS_COL_VG = 3;
    public static final int LVS_COL_POOL_LV = 4;
    public static final int LVS_COL_DATA_PERCENT = 5;
    public static final int LVS_COL_ATTRIBUTES = 6;

    private static String[] buildCmd(
        String baseCmd,
        String lvmConfig,
        String[] appendedString,
        String... baseOptions
    )
    {
        return buildCmd(baseCmd, lvmConfig, Arrays.asList(appendedString), baseOptions);
    }

    private static String[] buildCmd(
        String baseCmd,
        String lvmConfig,
        Collection<String> appendedString,
        String... baseOptions
    )
    {
        ArrayList<String> list = new ArrayList<>();
        list.add(baseCmd);
        if (lvmConfig != null && !lvmConfig.isEmpty())
        {
            list.add("--config");
            list.add(lvmConfig);
        }
        for (String baseOpt : baseOptions)
        {
            list.add(baseOpt);
        }
        if (appendedString != null)
        {
            list.addAll(appendedString);
        }
        String[] cmdArr = new String[list.size()];
        list.toArray(cmdArr);
        return cmdArr;
    }

    public static OutputData lvs(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig) throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvs",
                lvmConfig,
                volumeGroups,
                "-o", "lv_name,lv_path,lv_size,vg_name,pool_lv,data_percent,lv_attr",
                "--separator", LvmUtils.DELIMITER,
                "--noheadings",
                "--units", "k",
                "--nosuffix"
            ),
            "Failed to list lvm volumes",
            "Failed to query 'lvs' info",
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData getExtentSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "vgs",
                lvmConfig,
                volumeGroups,
                "-o", "vg_name,vg_extent_size",
                "--separator", LvmUtils.DELIMITER,
                "--units", "k",
                "--noheadings",
                "--nosuffix"
            ),
            "Failed to query lvm extent size",
            "Failed to query extent size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData createFat(
        ExtCmd extCmd,
        String volumeGroup,
        String vlmId,
        long size,
        String lvmConfig,
        String... additionalParameters
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvcreate",
                lvmConfig,
                additionalParameters,
                "--size", size + "k",
                "-n", volumeGroup + "/" + vlmId,
                "-y" // force, skip "wipe signature question"
            ),
            "Failed to create lvm volume",
            "Failed to create new lvm volume '" + vlmId + "' in volume group '" + volumeGroup +
                "' with size " + size + "kb"
        );
    }

    public static OutputData createThinPool(
        ExtCmd extCmd,
        String volumeGroupFull,
        String thinPoolName,
        String lvmConfig,
        String... additionalParameters
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvcreate",
                lvmConfig,
                additionalParameters,
                "-l", "100%FREE",
                "-T",
                "-n", volumeGroupFull + "/" +
                    thinPoolName
            ),
            "Failed to create lvm volume",
            "Failed to create new lvm thin pool in volume group '" + volumeGroupFull + "'"
        );
    }

    public static OutputData createThin(
        ExtCmd extCmd,
        String volumeGroup,
        String thinPoolName,
        String vlmId,
        long size,
        String lvmConfig,
        String... additionalParameters
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvcreate",
                lvmConfig,
                additionalParameters,
                "--virtualsize", size + "k", // -V
                "--thinpool", thinPoolName,
                "--name", volumeGroup + "/" + vlmId        // -n
            ),
            "Failed to create lvm volume",
            "Failed to create new lvm volume '" + vlmId + "' in volume group '" + volumeGroup +
            "' with size " + size + "kb"
        );
    }

    public static OutputData delete(ExtCmd extCmd, String volumeGroup, String vlmId, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvremove",
                lvmConfig,
                (Collection<String>) null,
                "-f", // skip the "are you sure?"
                volumeGroup + File.separator + vlmId
            ),
            "Failed to delete lvm volume",
            "Failed to delete lvm volume '" + vlmId + "' from volume group '" + volumeGroup,
            new RetryIfDeviceBusy()
        );
    }

    public static OutputData resize(ExtCmd extCmd, String volumeGroup, String vlmId, long size, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvresize",
                lvmConfig,
                (Collection<String>) null,
                "--size", size + "k",
                volumeGroup + File.separator + vlmId,
                "-f"
            ),
            "Failed to resize lvm volume",
            "Failed to resize lvm volume '" + vlmId + "' in volume group '" + volumeGroup + "' to size " + size
        );
    }

    public static OutputData rename(
        ExtCmd extCmd,
        String volumeGroup,
        String vlmCurrentId,
        String vlmNewId,
        String lvmConfig
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvrename",
                lvmConfig,
                (Collection<String>) null,
                volumeGroup,
                vlmCurrentId,
                vlmNewId
            ),
            "Failed to rename lvm volume from '" + vlmCurrentId + "' to '" + vlmNewId + "'",
            "Failed to rename lvm volume from '" + vlmCurrentId + "' to '" + vlmNewId + "'",
            new RetryHandler()
            {
                @Override
                public boolean retry(OutputData outputData)
                {
                    return false;
                }

                @Override
                public boolean skip(OutputData outData)
                {
                    boolean skip = false;

                    String err = new String(outData.stderrData);
                    if (err.contains("Volume group \"" + volumeGroup + "\" not found"))
                    {
                        // well - resource is gone... with the whole volume-group
                        skip = true;
                    }
                    return skip;
                }
            }
        );
    }

    public static OutputData createSnapshotThin(
        ExtCmd extCmd,
        String volumeGroup,
        String thinPool,
        String identifier,
        String snapshotIdentifier,
        String lvmConfig
    )
        throws StorageException
    {
        String failMsg = "Failed to create snapshot " + snapshotIdentifier + " from " + identifier +
            " within thin volume group " + volumeGroup + File.separator + thinPool;
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvcreate",
                lvmConfig,
                (Collection<String>) null,
                "--snapshot",
                "--setactivationskip", "y", // snapshot needs to be active from
                "--ignoreactivationskip", // the beginning for
                "--activate", "y", // snapshot-shipping to work
                "--name", snapshotIdentifier,
                volumeGroup + File.separator + identifier
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData restoreFromSnapshot(
        ExtCmd extCmd,
        String sourceLvIdWithSnapName,
        String volumeGroup,
        String targetId,
        String lvmConfig
    )
        throws StorageException
    {
        String failMsg = "Failed to restore snapshot " + sourceLvIdWithSnapName +
            " into new volume " + volumeGroup + File.separator + targetId;
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvcreate",
                lvmConfig,
                (Collection<String>) null,
                "--snapshot",
                "--name", targetId,
                volumeGroup + File.separator + sourceLvIdWithSnapName
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData rollbackToSnapshot(
        ExtCmd extCmd,
        String volumeGroup,
        String sourceResource,
        String lvmConfig
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvconvert",
                lvmConfig,
                (Collection<String>) null,
                "--merge",
                volumeGroup + File.separator + sourceResource
            ),
            "Failed to rollback to snapshot " + volumeGroup + File.separator + sourceResource,
            "Failed to rollback to snapshot " + volumeGroup + File.separator + sourceResource
        );
    }

    public static OutputData getVgTotalSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "vgs",
                lvmConfig,
                volumeGroups,
               "-o", "vg_name,vg_size",
                "--units", "k",
                "--separator", LvmUtils.DELIMITER,
                "--noheadings",
                "--nosuffix"
            ),
            "Failed to query total size of volume group(s) " + volumeGroups,
            "Failed to query total size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData getVgFreeSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "vgs",
                lvmConfig,
                volumeGroups,
                "-o", "vg_name,vg_free",
                "--units", "k",
                "--separator", LvmUtils.DELIMITER,
                "--noheadings",
                "--nosuffix"
            ),
            "Failed to query free size of volume group(s) " + volumeGroups,
            "Failed to query free size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData getVgThinTotalSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvs",
                lvmConfig,
                volumeGroups,
                "-o", "lv_name,lv_size",
                "--units", "k",
                "--separator", LvmUtils.DELIMITER,
                "--noheadings",
                "--nosuffix"
            ),
            "Failed to query total size of volume group(s) " + volumeGroups,
            "Failed to query total size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData getVgThinFreeSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            buildCmd(
                "vgs",
                lvmConfig,
                volumeGroups,
                "-o", "lv_name,lv_size,data_percent",
                "--units", "b", // intentionally not "k" as usual
                "--separator", LvmUtils.DELIMITER,
                "--noheadings",
                "--nosuffix"
            ),
            "Failed to query free size of volume group(s) " + volumeGroups,
            "Failed to query free size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData activateVolume(ExtCmd extCmd, String volumeGroup, String targetId, String lvmConfig)
        throws StorageException
    {
        String failMsg = "Failed to activate volume " + volumeGroup + File.separator + targetId;
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvchange",
                lvmConfig,
                (Collection<String>) null,
                "-ay",  // activate volume
                "-K",   // these parameters are needed to set a
                // snapshot to active and enabled
                volumeGroup + File.separator + targetId
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData deactivateVolume(ExtCmd extCmd, String volumeGroup, String targetId, String lvmConfig)
        throws StorageException
    {
        String failMsg = "Failed to deactivate volume " + volumeGroup + File.separator + targetId;
        return genericExecutor(
            extCmd,
            buildCmd(
                "lvchange",
                lvmConfig,
                (Collection<String>) null,
                "-an",  // deactivate volume
                volumeGroup + File.separator + targetId
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData listExistingVolumeGroups(ExtCmd extCmd, String lvmConfig) throws StorageException
    {
        String failMsg = "Failed to query list of volume groups";
        return genericExecutor(
            extCmd,
            buildCmd(
                "vgs",
                lvmConfig,
                (Collection<String>) null,
                "-o", "vg_name",
                "--noheadings"
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData pvCreate(ExtCmd extCmd, String devicePath, String lvmConfig) throws StorageException
    {
        final String failMsg = "Failed to pvcreate on device: " + devicePath;
        return genericExecutor(
            extCmd,
            buildCmd(
                "pvcreate",
                lvmConfig,
                (Collection<String>) null,
                devicePath
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData pvRemove(ExtCmd extCmd, Collection<String> devicePaths, String lvmConfig)
        throws StorageException
    {
        // no lvm config for pvremove!
        final String failMsg = "Failed to pvremove on device(s): " + String.join(", ", devicePaths);
        return genericExecutor(
            extCmd,
            buildCmd(
                "pvremove",
                lvmConfig,
                devicePaths
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData vgCreate(
        ExtCmd extCmd,
        final String vgName,
        final RaidLevel raidLevel,  // ignore for now as we only support JBOD
        final List<String> devicePaths,
        String lvmConfig
    )
        throws StorageException
    {
        // no lvm config for vgcreate!
        final String failMsg = "Failed to vgcreate on device(s): " + String.join(" ", devicePaths);
        return genericExecutor(
            extCmd,
            buildCmd(
                "vgcreate",
                lvmConfig,
                devicePaths,
                vgName
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData listPhysicalVolumes(ExtCmd extCmdRef, String volumeGroupRef, String lvmConfig)
        throws StorageException
    {
        final String failMsg = "Failed to get physical devices for volume group: " + volumeGroupRef;
        return genericExecutor(
            extCmdRef,
            buildCmd(
                "pvdisplay",
                null,
                (Collection<String>) null,
                "--columns",
                "-o",
                "pv_name",
                "-S",
                "vg_name=" + volumeGroupRef,
                "--noheadings",
                "--nosuffix"
            ),
            failMsg,
            failMsg
        );
    }


    public static OutputData vgRemove(
        ExtCmd extCmd,
        final String vgName,
        String lvmConfig
    )
        throws StorageException
    {
        // no lvm config for vgremove!
        final String failMsg = "Failed to vgremove on volume group: " + vgName;
        return genericExecutor(
            extCmd,
            buildCmd(
                "vgremove",
                lvmConfig,
                (Collection<String>) null,
                vgName
            ),
            failMsg,
            failMsg
        );
    }

    private LvmCommands()
    {
    }

}
