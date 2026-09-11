/* Cost-bound experiment only: removes BC initialization/registration, changing crypto behavior. */
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

public final class OmitBouncyCastle {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("input.jar output.jar");
    if (Files.exists(Path.of(args[1]))) throw new IllegalArgumentException("Output exists");
    int[] edits = new int[3];
    try (JarFile jar = new JarFile(args[0]);
         JarOutputStream out = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        byte[] bytes = jar.getInputStream(entry).readAllBytes();
        if (entry.getName().equals("org/robolectric/android/internal/AndroidTestEnvironment.class")) {
          String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
          if (!hash.equals("330d3fd538e82e0d1321da943f1e3f8f48af3bd82debfb3259be1b3d069f8c48"))
            throw new IllegalArgumentException("Unexpected AndroidTestEnvironment: " + hash);
          ClassWriter writer = new ClassWriter(0);
          new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
              MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions);
              boolean initializer = name.equals("<clinit>");
              return new MethodVisitor(Opcodes.ASM9, target) {
                boolean skipDup, bcField;
                @Override public void visitTypeInsn(int op, String type) {
                  if (initializer && op == Opcodes.NEW && type.equals("org/bouncycastle/jce/provider/BouncyCastleProvider")) {
                    super.visitInsn(Opcodes.ACONST_NULL);
                    skipDup = true;
                    edits[0]++;
                  } else super.visitTypeInsn(op, type);
                }
                @Override public void visitInsn(int op) {
                  if (skipDup && op == Opcodes.DUP) { skipDup = false; return; }
                  super.visitInsn(op);
                }
                @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
                  bcField = op == Opcodes.GETSTATIC && name.equals("BOUNCY_CASTLE_PROVIDER");
                  super.visitFieldInsn(op, owner, name, desc);
                }
                @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                  if (initializer && owner.equals("org/bouncycastle/jce/provider/BouncyCastleProvider") && name.equals("<init>")) {
                    edits[1]++;
                    return;
                  }
                  if (bcField && owner.equals("java/security/Security") && name.equals("addProvider")) {
                    super.visitInsn(Opcodes.POP);
                    super.visitInsn(Opcodes.ICONST_M1);
                    bcField = false;
                    edits[2]++;
                    return;
                  }
                  super.visitMethodInsn(op, owner, name, desc, itf);
                }
              };
            }
          }, 0);
          bytes = writer.toByteArray();
        }
        JarEntry copy = new JarEntry(entry.getName());
        copy.setTime(0);
        out.putNextEntry(copy); out.write(bytes); out.closeEntry();
      }
    }
    if (!Arrays.equals(edits, new int[]{1, 1, 1}))
      throw new IllegalStateException("Unexpected edits: " + Arrays.toString(edits));
  }
}
