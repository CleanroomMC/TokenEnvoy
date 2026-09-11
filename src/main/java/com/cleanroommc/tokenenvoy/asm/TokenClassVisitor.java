/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy.asm;

import com.cleanroommc.tokenenvoy.Tokens;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;

import java.util.List;
import java.util.Map;

final class TokenClassVisitor extends ClassVisitor {

    private final List<Map.Entry<String, String>> tokens;

    TokenClassVisitor(ClassVisitor parent, List<Map.Entry<String, String>> tokens) {
        super(Opcodes.ASM9, parent);
        this.tokens = tokens;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        return wrap(super.visitAnnotation(descriptor, visible));
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
        return wrap(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible));
    }

    @Override
    public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
        RecordComponentVisitor visitor = super.visitRecordComponent(name, descriptor, signature);
        return visitor == null ? null : new TokenRecordComponentVisitor(visitor, this.tokens);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        FieldVisitor visitor = super.visitField(access, name, descriptor, signature, Tokens.replaceValue(value, this.tokens));
        return visitor == null ? null : new TokenFieldVisitor(visitor, this.tokens);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        return visitor == null ? null : new TokenMethodVisitor(visitor, this.tokens);
    }

    private AnnotationVisitor wrap(AnnotationVisitor visitor) {
        return visitor == null ? null : new TokenAnnotationVisitor(visitor, this.tokens);
    }

    private static final class TokenFieldVisitor extends FieldVisitor {

        private final List<Map.Entry<String, String>> tokens;

        TokenFieldVisitor(FieldVisitor parent, List<Map.Entry<String, String>> tokens) {
            super(Opcodes.ASM9, parent);
            this.tokens = tokens;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return wrap(super.visitAnnotation(descriptor, visible));
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
            return wrap(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible));
        }

        private AnnotationVisitor wrap(AnnotationVisitor visitor) {
            return visitor == null ? null : new TokenAnnotationVisitor(visitor, this.tokens);
        }

    }

    private static final class TokenRecordComponentVisitor extends RecordComponentVisitor {

        private final List<Map.Entry<String, String>> tokens;

        TokenRecordComponentVisitor(RecordComponentVisitor parent, List<Map.Entry<String, String>> tokens) {
            super(Opcodes.ASM9, parent);
            this.tokens = tokens;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return wrap(super.visitAnnotation(descriptor, visible));
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
            return wrap(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible));
        }

        private AnnotationVisitor wrap(AnnotationVisitor visitor) {
            return visitor == null ? null : new TokenAnnotationVisitor(visitor, this.tokens);
        }

    }

    private static final class TokenMethodVisitor extends MethodVisitor {

        private final List<Map.Entry<String, String>> tokens;

        TokenMethodVisitor(MethodVisitor parent, List<Map.Entry<String, String>> tokens) {
            super(Opcodes.ASM9, parent);
            this.tokens = tokens;
        }

        @Override
        public void visitLdcInsn(Object value) {
            super.visitLdcInsn(Tokens.replaceValue(value, this.tokens));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, Tokens.replaceValues(bootstrapMethodArguments, this.tokens));
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return wrap(super.visitAnnotationDefault());
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return wrap(super.visitAnnotation(descriptor, visible));
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
            return wrap(super.visitTypeAnnotation(typeRef, typePath, descriptor, visible));
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            return wrap(super.visitParameterAnnotation(parameter, descriptor, visible));
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
            return wrap(super.visitInsnAnnotation(typeRef, typePath, descriptor, visible));
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
            return wrap(super.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible));
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(
                int typeRef,
                org.objectweb.asm.TypePath typePath,
                org.objectweb.asm.Label[] start,
                org.objectweb.asm.Label[] end,
                int[] index,
                String descriptor,
                boolean visible
        ) {
            return wrap(super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index, descriptor, visible));
        }

        private AnnotationVisitor wrap(AnnotationVisitor visitor) {
            return visitor == null ? null : new TokenAnnotationVisitor(visitor, this.tokens);
        }

    }

    private static final class TokenAnnotationVisitor extends AnnotationVisitor {

        private final List<Map.Entry<String, String>> tokens;

        TokenAnnotationVisitor(AnnotationVisitor parent, List<Map.Entry<String, String>> tokens) {
            super(Opcodes.ASM9, parent);
            this.tokens = tokens;
        }

        @Override
        public void visit(String name, Object value) {
            super.visit(name, Tokens.replaceValue(value, this.tokens));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            AnnotationVisitor visitor = super.visitAnnotation(name, descriptor);
            return visitor == null ? null : new TokenAnnotationVisitor(visitor, this.tokens);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            AnnotationVisitor visitor = super.visitArray(name);
            return visitor == null ? null : new TokenAnnotationVisitor(visitor, this.tokens);
        }

    }

}
