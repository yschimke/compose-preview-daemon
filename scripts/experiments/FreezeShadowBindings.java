/* Test-only bytecode experiment. Never modifies the Gradle cache or production classpath. */
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.jar.*;
import org.objectweb.asm.*;

public final class FreezeShadowBindings {
  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !Set.of("frozen", "constant").contains(args[2])) {
      throw new IllegalArgumentException("input-sandbox.jar output.jar frozen|constant");
    }
    Path input = Path.of(args[0]), output = Path.of(args[1]);
    if (Files.exists(output)) throw new IllegalArgumentException("Output already exists");
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
    if (!digest.equals("afd922c4b79db9b38d6eba6bd3e3e8ff2c4b01d5baf43c1178455cbfacaecc1e")) {
      throw new IllegalArgumentException("Expected the measured Robolectric sandbox jar (4.17-beta-4/4.17), got " + digest);
    }
    boolean constant = args[2].equals("constant");
    int[] rewritten = {0, 0, 0};
    try (JarFile jar = new JarFile(input.toFile());
         JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        byte[] bytes = jar.getInputStream(entry).readAllBytes();
        if (entry.getName().equals("org/robolectric/internal/bytecode/InvokeDynamicSupport.class")) {
          ClassReader reader = new ClassReader(bytes);
          ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
          reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
              if (name.equals("bindWithFallback") && desc.equals(
                  "(Lorg/robolectric/internal/bytecode/RoboCallSite;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;")) {
                rewritten[0]++;
                MethodVisitor m = super.visitMethod(access, name, desc, signature, exceptions);
                m.visitCode();
                m.visitMethodInsn(Opcodes.INVOKESTATIC, "org/robolectric/internal/bytecode/RobolectricInternals", "getShadowInvalidator", "()Lorg/robolectric/internal/bytecode/ShadowInvalidator;", false);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/robolectric/internal/bytecode/ShadowInvalidator", "markBindingsFrozen", "()V", false);
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/robolectric/internal/bytecode/RoboCallSite", "type", "()Ljava/lang/invoke/MethodType;", false);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
                m.visitVarInsn(Opcodes.ASTORE, 1);
                m.visitVarInsn(Opcodes.ALOAD, 0);
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/robolectric/internal/bytecode/RoboCallSite", "setTarget", "(Ljava/lang/invoke/MethodHandle;)V", false);
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitInsn(Opcodes.ARETURN);
                m.visitMaxs(0, 0);
                m.visitEnd();
                return null;
              }
              MethodVisitor m = super.visitMethod(access, name, desc, signature, exceptions);
              if (constant && Set.of("bootstrap", "bootstrapStatic", "bootstrapInit").contains(name)) {
                rewritten[1]++;
                return new MethodVisitor(Opcodes.ASM9, m) {
                  @Override public void visitInsn(int opcode) {
                    if (opcode == Opcodes.ARETURN) {
                      super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/CallSite", "getTarget", "()Ljava/lang/invoke/MethodHandle;", false);
                      super.visitTypeInsn(Opcodes.NEW, "java/lang/invoke/ConstantCallSite");
                      super.visitInsn(Opcodes.DUP_X1);
                      super.visitInsn(Opcodes.SWAP);
                      super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false);
                    }
                    super.visitInsn(opcode);
                  }
                };
              }
              return m;
            }
          }, 0);
          bytes = writer.toByteArray();
        }
        if (entry.getName().equals("org/robolectric/internal/bytecode/ShadowInvalidator.class")) {
          ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
          new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                String signature, String[] exceptions) {
              MethodVisitor m = super.visitMethod(access, name, desc, signature, exceptions);
              if (!name.equals("invalidateClasses") || !desc.equals("(Ljava/util/Collection;)V")) return m;
              rewritten[2]++;
              return new MethodVisitor(Opcodes.ASM9, m) {
                @Override public void visitCode() {
                  super.visitCode();
                  Label allowed = new Label();
                  visitVarInsn(Opcodes.ALOAD, 0);
                  visitFieldInsn(Opcodes.GETFIELD, "org/robolectric/internal/bytecode/ShadowInvalidator", "bindingsFrozen", "Z");
                  visitJumpInsn(Opcodes.IFEQ, allowed);
                  visitVarInsn(Opcodes.ALOAD, 1);
                  visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "isEmpty", "()Z", true);
                  visitJumpInsn(Opcodes.IFNE, allowed);
                  visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
                  visitInsn(Opcodes.DUP);
                  visitLdcInsn("Experimental frozen bindings cannot change shadow maps after binding");
                  visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
                  visitInsn(Opcodes.ATHROW);
                  visitLabel(allowed);
                  visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                }
              };
            }
            @Override public void visitEnd() {
              super.visitField(Opcodes.ACC_PRIVATE, "bindingsFrozen", "Z", null, null).visitEnd();
              MethodVisitor m = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
                  "markBindingsFrozen", "()V", null, null);
              m.visitCode();
              m.visitVarInsn(Opcodes.ALOAD, 0);
              m.visitInsn(Opcodes.ICONST_1);
              m.visitFieldInsn(Opcodes.PUTFIELD, "org/robolectric/internal/bytecode/ShadowInvalidator", "bindingsFrozen", "Z");
              m.visitInsn(Opcodes.RETURN);
              m.visitMaxs(0, 0);
              m.visitEnd();
              super.visitEnd();
            }
          }, 0);
          bytes = writer.toByteArray();
        }
        JarEntry copy = new JarEntry(entry.getName());
        copy.setTime(0);
        out.putNextEntry(copy);
        out.write(bytes);
        out.closeEntry();
      }
    }
    if (rewritten[0] != 1 || rewritten[1] != (constant ? 3 : 0) || rewritten[2] != 1) {
      Files.delete(output);
      throw new IllegalStateException("Unexpected sandbox implementation: " + Arrays.toString(rewritten));
    }
    System.out.println("Patched " + output + ": " + Arrays.toString(rewritten));
  }
}
