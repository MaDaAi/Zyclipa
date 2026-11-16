package io.zyclipa;

import io.zyclipa.platform.Desktop.Foreground;
import io.zyclipa.recording.GameNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Instant replay only switches itself on for real games. */
class GameDetectTest {
    static boolean game(String process, String path, boolean full) { return GameNames.isLikelyGame(new Foreground("t", process, path, full)); }

    @Test void gamesAreRecognised() {
        assertTrue(game("RobloxPlayerBeta", "C:\\Users\\a\\AppData\\Local\\Roblox\\Versions\\v1\\RobloxPlayerBeta.exe", false), "known game");
        assertTrue(game("eldenring", "D:\\SteamLibrary\\steamapps\\common\\ELDEN RING\\Game\\eldenring.exe", false), "Steam folder");
        assertTrue(game("SomeIndie", "E:\\Epic Games\\Indie\\SomeIndie.exe", false), "Epic folder");
        assertTrue(game("weirdgame", "C:\\Games\\weird\\weirdgame.exe", true), "anything else full screen");
    }

    @Test void everydayAppsAreNot() {
        assertFalse(game("chrome", "C:\\Program Files\\Google\\Chrome\\chrome.exe", true), "browser full screen (video)");
        assertFalse(game("Discord", "C:\\Users\\a\\AppData\\Local\\Discord\\app\\Discord.exe", false));
        assertFalse(game("Code", "C:\\Programs\\VS Code\\Code.exe", false), "windowed app that isn't a game");
        assertFalse(game("explorer", "C:\\Windows\\explorer.exe", true));
        assertFalse(game("Zyclipa", "G:\\apps\\Zyclipa\\Zyclipa.exe", true), "never itself");
        assertFalse(game("SystemSettings", "C:\\Windows\\ImmersiveControlPanel\\SystemSettings.exe", true));
        assertFalse(game("", "", true));
    }
}
