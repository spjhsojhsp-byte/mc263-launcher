package net.kdt.pojavlaunch.tasks;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

public class AsyncMinecraftDownloader {
    public static String normalizeVersionId(String versionString) {
        JMinecraftVersionList versionList = (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if(versionList == null || versionList.versions == null) return versionString;
        if(MinecraftProfile.LATEST_RELEASE.equals(versionString)) versionString = versionList.latest.get("release");
        if(MinecraftProfile.LATEST_SNAPSHOT.equals(versionString)) versionString = versionList.latest.get("snapshot");
        return versionString;
    }

    public static JMinecraftVersionList.Version getListedVersion(String normalizedVersionString) {
        JMinecraftVersionList versionList = (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if(versionList == null || versionList.versions == null) return null; // can't have listed versions if there's no list
        for(JMinecraftVersionList.Version version : versionList.versions) {
            if(version.id.equals(normalizedVersionString)) return version;
        }
        return null;
    }

    /**
     * The version Play starts: the newest release in the version list, or the saved id while the list is not loaded.
     * @param savedVersionId The version id saved in the profile
     */
    public static String getPlayVersionId(String savedVersionId) {
        String latestRelease = normalizeVersionId(MinecraftProfile.LATEST_RELEASE);
        if (latestRelease != null && !MinecraftProfile.LATEST_RELEASE.equals(latestRelease)) return latestRelease;
        return savedVersionId;
    }

    /** Whether the game version is already downloaded for that account type, so it can start without the internet */
    public static boolean isVersionInstalled(String versionId, boolean demo) {
        String gameDir = demo ? Tools.DIR_DATA + "/demo/.minecraft" : Tools.DIR_GAME_HOME + "/.minecraft";
        File versionDir = new File(gameDir + "/versions/" + versionId);
        // The json must not be empty (offline start reads it), and the game runs from the client jar
        return new File(versionDir, versionId + ".json").length() > 0 && new File(versionDir, versionId + ".jar").exists();
    }

    public interface DoneListener{
        void onDownloadDone();
        void onDownloadFailed(Throwable throwable);
    }
}
