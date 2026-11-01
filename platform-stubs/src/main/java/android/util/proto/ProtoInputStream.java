package android.util.proto;

import java.io.InputStream;
import java.io.IOException;

/** Compile-only Android 10 proto reader signatures. Never packaged. */
public class ProtoInputStream {
    public ProtoInputStream(InputStream stream) { }
    public int nextField() throws IOException { throw new UnsupportedOperationException("compile-only"); }
    public long start(long fieldId) throws IOException { throw new UnsupportedOperationException("compile-only"); }
    public void end(long token) throws IOException { throw new UnsupportedOperationException("compile-only"); }
    public int readInt(long fieldId) throws IOException { throw new UnsupportedOperationException("compile-only"); }
    public float readFloat(long fieldId) throws IOException { throw new UnsupportedOperationException("compile-only"); }
    public boolean readBoolean(long fieldId) throws IOException { throw new UnsupportedOperationException("compile-only"); }
    public String readString(long fieldId) throws IOException { throw new UnsupportedOperationException("compile-only"); }
}
