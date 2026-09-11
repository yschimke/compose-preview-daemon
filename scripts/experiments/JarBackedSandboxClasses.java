/* Offline experiment: standard jar loading for uninstrumented Compose/Kotlin classes only. */
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

public final class JarBackedSandboxClasses {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("input-sandbox.jar output.jar");
    Path output = Path.of(args[1]);
    if (Files.exists(output)) throw new IllegalArgumentException("Output already exists");
    int[] matches = {0};
    try (JarFile jar = new JarFile(args[0]);
         JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        byte[] bytes = jar.getInputStream(entry).readAllBytes();
        if (entry.getName().equals("org/robolectric/internal/bytecode/SandboxClassLoader.class")) {
          String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
          if (!digest.equals("f044e156d33c894931852d21edde90d6e1a74e635d9a132095aad735f886ee28")) {
            throw new IllegalArgumentException("Expected the measured Robolectric SandboxClassLoader, got " + digest);
          }
          ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
          new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
              MethodVisitor m = super.visitMethod(access, name, desc, signature, exceptions);
              if (!name.equals("maybeInstrumentClass") || !desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")) return m;
              return new MethodVisitor(Opcodes.ASM9, m) {
                boolean shouldInstrument;
                @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                  super.visitMethodInsn(opcode, owner, name, desc, itf);
                  shouldInstrument = owner.equals("org/robolectric/internal/bytecode/InstrumentationConfiguration") && name.equals("shouldInstrument");
                }
                @Override public void visitJumpInsn(int opcode, Label original) {
                  if (!shouldInstrument || opcode != Opcodes.IFEQ) {
                    super.visitJumpInsn(opcode, original);
                    return;
                  }
                  shouldInstrument = false;
                  matches[0]++;
                  Label instrument = new Label(), standard = new Label();
                  super.visitJumpInsn(Opcodes.IFNE, instrument);
                  for (String prefix : List.of("androidx.", "kotlin.", "kotlinx.")) {
                    super.visitVarInsn(Opcodes.ALOAD, 1);
                    super.visitLdcInsn(prefix);
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;)Z", false);
                    super.visitJumpInsn(Opcodes.IFNE, standard);
                  }
                  super.visitJumpInsn(Opcodes.GOTO, original);
                  super.visitLabel(standard);
                  frame();
                  super.visitVarInsn(Opcodes.ALOAD, 0);
                  super.visitVarInsn(Opcodes.ALOAD, 1);
                  super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URLClassLoader", "findClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
                  super.visitInsn(Opcodes.ARETURN);
                  super.visitLabel(instrument);
                  frame();
                }
                private void frame() {
                  super.visitFrame(Opcodes.F_NEW, 4, new Object[]{
                      "org/robolectric/internal/bytecode/SandboxClassLoader", "java/lang/String",
                      "[B", "org/robolectric/internal/bytecode/ClassDetails"}, 0, new Object[]{});
                }
              };
            }
          }, ClassReader.EXPAND_FRAMES);
          bytes = writer.toByteArray();
        }
        JarEntry copy = new JarEntry(entry.getName());
        copy.setTime(0);
        out.putNextEntry(copy);
        out.write(bytes);
        out.closeEntry();
      }
    }
    if (matches[0] != 1) {
      Files.delete(output);
      throw new IllegalStateException("Expected one shouldInstrument branch, got " + matches[0]);
    }
    System.out.println("Patched " + output);
  }
}
