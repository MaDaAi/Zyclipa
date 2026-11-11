package io.zyclipra.cloud;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import io.zyclipra.core.Log;
import io.zyclipra.core.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Keeps a secret in Windows Credential Manager (a "generic" credential for the signed-in Windows
 * user, stored on this PC only). If Credential Manager isn't available – or on other systems – it
 * falls back to the {@link SecretStore} file, which on Windows is itself DPAPI-encrypted. A secret
 * found in the old file is moved into Credential Manager and the file is deleted.
 */
public final class CredentialStore implements Secrets {
    private static final Logger LOG = Log.get(CredentialStore.class);
    private static final int CRED_TYPE_GENERIC = 1, CRED_PERSIST_LOCAL_MACHINE = 2, ERROR_NOT_FOUND = 1168, MAX_BLOB = 2560;

    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWrittenLow", "LastWrittenHigh", "CredentialBlobSize",
            "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName"})
    public static class CREDENTIAL extends Structure {
        public int Flags, Type;
        public WString TargetName, Comment;
        public int LastWrittenLow, LastWrittenHigh;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist, AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias, UserName;
        public CREDENTIAL() { }
        public CREDENTIAL(Pointer p) { super(p); read(); }
    }

    interface Advapi extends Library {
        boolean CredWriteW(CREDENTIAL credential, int flags);
        boolean CredReadW(WString target, int type, int flags, PointerByReference credential);
        boolean CredDeleteW(WString target, int type, int flags);
        void CredFree(Pointer buffer);
    }

    private static volatile Advapi api;
    private static volatile boolean apiFailed;

    private static Advapi api() {
        if (api == null && !apiFailed && Platform.isWindows()) {
            try { api = Native.load("Advapi32", Advapi.class); }
            catch (Throwable t) { apiFailed = true; LOG.warning("Credential Manager unavailable: " + t); }
        }
        return api;
    }

    private final String target;
    private final SecretStore fallback;

    /** @param target the credential's name in Credential Manager, e.g. "Zyclipra/google". */
    public CredentialStore(String target, SecretStore fallback) {
        this.target = target;
        this.fallback = fallback;
    }

    /** Whether secrets really go to Credential Manager (false = the encrypted file is used). */
    public static boolean available() { return api() != null; }

    @Override public void save(String secret) throws IOException {
        Advapi a = api();
        byte[] blob = secret.getBytes(StandardCharsets.UTF_8);
        if (a == null || blob.length > MAX_BLOB) { fallback.save(secret); return; }
        Memory mem = new Memory(Math.max(1, blob.length));
        mem.write(0, blob, 0, blob.length);
        CREDENTIAL c = new CREDENTIAL();
        c.Type = CRED_TYPE_GENERIC;
        c.TargetName = new WString(target);
        c.Comment = new WString("Zyclipra – lets Zyclipra upload your clips to Google Drive. Remove it to sign Zyclipra out.");
        c.CredentialBlobSize = blob.length;
        c.CredentialBlob = mem;
        c.Persist = CRED_PERSIST_LOCAL_MACHINE;
        c.UserName = new WString("zyclipra");
        boolean ok = a.CredWriteW(c, 0);
        mem.clear();
        Arrays.fill(blob, (byte) 0);
        if (!ok) throw new IOException("Windows Credential Manager refused to save (error " + Native.getLastError() + ")");
        fallback.clear();
    }

    @Override public String load() {
        Advapi a = api();
        if (a == null) return fallback.load();
        PointerByReference ref = new PointerByReference();
        if (a.CredReadW(new WString(target), CRED_TYPE_GENERIC, 0, ref)) {
            try {
                CREDENTIAL c = new CREDENTIAL(ref.getValue());
                byte[] b = c.CredentialBlob == null ? new byte[0] : c.CredentialBlob.getByteArray(0, c.CredentialBlobSize);
                return new String(b, StandardCharsets.UTF_8);
            } finally {
                a.CredFree(ref.getValue());
            }
        }
        int err = Native.getLastError();
        String old = fallback.load();
        if (old != null) {
            try { save(old); LOG.info("Moved a saved secret into Windows Credential Manager"); }
            catch (IOException e) { LOG.warning("Couldn't move secret into Credential Manager: " + e.getMessage()); }
            return old;
        }
        if (err != ERROR_NOT_FOUND && err != 0) LOG.warning("Credential Manager read failed (error " + err + ")");
        return null;
    }

    @Override public void clear() throws IOException {
        Advapi a = api();
        if (a != null && !a.CredDeleteW(new WString(target), CRED_TYPE_GENERIC, 0)) {
            int err = Native.getLastError();
            if (err != ERROR_NOT_FOUND) LOG.warning("Credential Manager delete failed (error " + err + ")");
        }
        fallback.clear();
    }
}
