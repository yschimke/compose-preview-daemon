/* Offline overlap experiment: construct BC asynchronously, join at original registration. */
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

public final class AsyncBouncyCastle {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("input.jar output.jar factory.class");
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
            @Override public FieldVisitor visitField(int access, String name, String desc,
                String signature, Object value) {
              if (name.equals("BOUNCY_CASTLE_PROVIDER")) {
                desc = "Ljava/util/concurrent/CompletableFuture;";
                signature = null;
              }
              return super.visitField(access, name, desc, signature, value);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
              MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions);
              boolean initializer = name.equals("<clinit>");
              return new MethodVisitor(Opcodes.ASM9, target) {
                boolean skipDup;
                @Override public void visitTypeInsn(int op, String type) {
                  if (initializer && op == Opcodes.NEW && type.equals("org/bouncycastle/jce/provider/BouncyCastleProvider")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/robolectric/android/internal/AsyncBouncyCastleFactory", "start",
                        "()Ljava/util/concurrent/CompletableFuture;", false);
                    skipDup = true;
                    edits[0]++;
                  } else super.visitTypeInsn(op, type);
                }
                @Override public void visitInsn(int op) {
                  if (skipDup && op == Opcodes.DUP) { skipDup = false; return; }
                  super.visitInsn(op);
                }
                @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
                  boolean provider = name.equals("BOUNCY_CASTLE_PROVIDER");
                  super.visitFieldInsn(op, owner, name,
                      provider ? "Ljava/util/concurrent/CompletableFuture;" : desc);
                  if (provider && op == Opcodes.GETSTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture",
                        "join", "()Ljava/lang/Object;", false);
                    super.visitTypeInsn(Opcodes.CHECKCAST, "org/bouncycastle/jce/provider/BouncyCastleProvider");
                    edits[2]++;
                  }
                }
                @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                  if (initializer && owner.equals("org/bouncycastle/jce/provider/BouncyCastleProvider") && name.equals("<init>")) {
                    edits[1]++;
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
      JarEntry factory = new JarEntry("org/robolectric/android/internal/AsyncBouncyCastleFactory.class");
      factory.setTime(0);
      out.putNextEntry(factory); out.write(Files.readAllBytes(Path.of(args[2]))); out.closeEntry();
    }
    if (!Arrays.equals(edits, new int[]{1, 1, 1}))
      throw new IllegalStateException("Unexpected edits: " + Arrays.toString(edits));
  }
}
