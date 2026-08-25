package com.legacyrecon.parser.java;

import com.legacyrecon.parser.api.*;
import com.legacyrecon.ucm.id.DeterministicId;
import com.legacyrecon.ucm.id.JvmDescriptor;
import com.legacyrecon.ucm.model.*;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;

import java.util.*;

/**
 * 02.2 Java 解析器（JDT）实现。
 * 脚手架采用结构式 DOM 解析（K_COMPILATION_UNIT）：提取类/接口/枚举/注解、方法/构造器、
 * 字段、参数，并按 01 文档生成实体、关系、确定性 ID 与 JVM 描述符签名。
 * 完整工程级绑定（setResolveBindings + classpath）留作生产增强（02.2）。
 */
public class JdtParser implements LanguageParser {

    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

    @Override
    public String language() {
        return "java";
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        long t0 = System.nanoTime();
        ParseResult out = new ParseResult("Java");
        Set<String> projectTypes = new HashSet<>();
        Set<String> projectIds = new HashSet<>();
        List<FileUnit> units = new ArrayList<>();

        // 阶段一：解析每个文件，产出实体（File/类型/成员/参数），收集项目内类型
        for (SourceFile sf : request.files) {
            if (sf.content == null) {
                continue; // 幂等：删除文件不产出实体
            }
            FileUnit u;
            try {
                u = new FileUnit(sf);
            } catch (Exception ex) {
                out.issues.add(ParseIssue.error("JAVA.PARSE_FAILED",
                        "文件解析失败：" + ex.getMessage(), new SourceLocation(sf.path, 1, 1, 1, 1)));
                continue;
            }
            units.add(u);
            projectTypes.addAll(u.projectTypes);
            projectIds.addAll(u.projectIds);
            out.issues.addAll(u.issues);
        }

        // 阶段二：合并实体与文件索引
        for (FileUnit u : units) {
            u.registerInto(out, projectTypes, projectIds);
        }

        // 阶段三：关系 + DEPENDS_ON
        for (FileUnit u : units) {
            out.relations.addAll(u.relations);
            out.relations.addAll(u.dependsOn);
        }

        out.addStats("fileCount", units.size());
        out.addStats("entityCount", out.entities.size());
        out.addStats("relationCount", out.relations.size());
        out.addStats("issueCount", out.issues.size());
        out.addStats("durationMs", Math.max(1, (System.nanoTime() - t0) / 1_000_000));
        return out;
    }

    /**
     * 单个文件的解析结果。持有 CompilationUnit 以便计算位置。
     */
    private class FileUnit {
        final SourceFile sf;
        CompilationUnit cu;
        final Entity fileEntity;
        final List<Entity> types = new ArrayList<>();
        final List<Entity> members = new ArrayList<>();
        final List<Entity> params = new ArrayList<>();
        final List<Relation> relations = new ArrayList<>();
        final List<Relation> dependsOn = new ArrayList<>();
        final List<ParseIssue> issues = new ArrayList<>();
        final Set<String> projectTypes = new HashSet<>();
        final Set<String> projectIds = new HashSet<>();

        FileUnit(SourceFile sf) throws Exception {
            this.sf = sf;
            this.cu = parseAst(sf.content, sf.path);
            fileEntity = buildFileEntity();

            String pkg = cu.getPackage() == null ? "" : cu.getPackage().getName().getFullyQualifiedName();
            for (Object o : cu.types()) {
                AbstractTypeDeclaration type = (AbstractTypeDeclaration) o;
                collectType(type, pkg, true);
            }
            // 文件 -> 顶级类型（已在 collectType(isTop=true) 中加入）
            // DEPENDS_ON：import 引入
            for (Object o : cu.imports()) {
                ImportDeclaration imp = (ImportDeclaration) o;
                String qn = imp.getName().getFullyQualifiedName();
                dependsOn.add(dependsOn(fileEntity, qn, loc(imp)));
            }
            // 项目内类型（用于外部判定）：File + 各类型 qname
            projectTypes.add(sf.path);
            for (Entity t : types) {
                projectTypes.add(t.qualifiedName);
            }
            // 项目内 ID 集合
            projectIds.add(fileEntity.id);
            for (Entity e : types) {
                projectIds.add(e.id);
            }
            for (Entity e : members) {
                projectIds.add(e.id);
            }
        }

        private Entity buildFileEntity() {
            Entity e = new Entity();
            e.id = DeterministicId.typeEntity("java", sf.path);
            e.type = EntityType.File;
            e.name = fileName(sf.path);
            e.qualifiedName = sf.path;
            e.language = "Java";
            int lastLine = Math.max(1, cu.getLineNumber(Math.max(0, cu.getLength())));
            e.location = new SourceLocation(sf.path, 1, 1, lastLine + 1, 1);
            return e;
        }

        /** 解析 AST，语法问题映射为 WARNING issue（恢复式遍历，不中断） */
        private CompilationUnit parseAst(String content, String path) {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(false);
            Map<String, String> opts = JavaCore.getOptions();
            JavaCore.setComplianceOptions(JavaCore.VERSION_17, opts);
            parser.setCompilerOptions(opts);
            parser.setSource(content.toCharArray());
            CompilationUnit cu = (CompilationUnit) parser.createAST(null);
            for (IProblem problem : cu.getProblems()) {
                SourceLocation l = loc(problem.getSourceStart(), problem.getSourceEnd());
                if (problem.isError()) {
                    issues.add(ParseIssue.warning("JAVA.SYNTAX", problem.getMessage(), l));
                }
            }
            return cu;
        }

        /** 收集一个类型声明（含其成员与嵌套类型）；isTop 时建立 File CONTAINS 边 */
        void collectType(AbstractTypeDeclaration type, String enclosingQname, boolean isTop) {
            Entity t = new Entity();
            String qname = enclosingQname.isEmpty() ? type.getName().getIdentifier()
                    : enclosingQname + "." + type.getName().getIdentifier();
            if (type instanceof TypeDeclaration td) {
                t.type = td.isInterface() ? EntityType.Interface : EntityType.Class;
            } else if (type instanceof EnumDeclaration) {
                t.type = EntityType.Enum;
            } else if (type instanceof AnnotationTypeDeclaration) {
                t.type = EntityType.Annotation;
            } else if (type instanceof RecordDeclaration) {
                t.type = EntityType.Class;
            } else {
                return;
            }
            t.name = type.getName().getIdentifier();
            t.qualifiedName = qname;
            t.id = DeterministicId.typeEntity("java", qname);
            t.language = "Java";
            t.location = loc(type);
            collectModifiers(modifiersOf(type), t);
            docComment(t, type);
            types.add(t);
            projectTypes.add(qname);

            relations.add(isTop
                    ? contains(fileEntity, t)
                    : contains(findType(enclosingQname), t));

            collectMembers(type, t);
        }

        void collectMembers(AbstractTypeDeclaration type, Entity typeEntity) {
            List<BodyDeclaration> bodies = bodyDecls(type);
            for (BodyDeclaration body : bodies) {
                if (body instanceof FieldDeclaration f) {
                    for (Object fo : f.fragments()) {
                        VariableDeclarationFragment frag = (VariableDeclarationFragment) fo;
                        Entity field = new Entity();
                        String fq = typeEntity.qualifiedName + "." + frag.getName().getIdentifier();
                        field.id = DeterministicId.fieldEntity("java", fq);
                        field.type = EntityType.Field;
                        field.name = frag.getName().getIdentifier();
                        field.qualifiedName = fq;
                        field.language = "Java";
                        field.location = loc(frag);
                        field.typeRef = typeRefFrom(f.getType());
                        collectModifiers(f.modifiers(), field);
                        members.add(field);
                        projectIds.add(field.id);
                        relations.add(contains(typeEntity, field));
                    }
                } else if (body instanceof MethodDeclaration md) {
                    collectMethod(md, typeEntity);
                } else if (body instanceof AbstractTypeDeclaration nested) {
                    collectType(nested, typeEntity.qualifiedName, false);
                }
            }
            // 枚举常量
            if (type instanceof EnumDeclaration ed) {
                for (Object o : ed.enumConstants()) {
                    EnumConstantDeclaration ec = (EnumConstantDeclaration) o;
                    Entity c = new Entity();
                    String fq = typeEntity.qualifiedName + "." + ec.getName().getIdentifier();
                    c.id = DeterministicId.fieldEntity("java", fq);
                    c.type = EntityType.EnumConstant;
                    c.name = ec.getName().getIdentifier();
                    c.qualifiedName = fq;
                    c.language = "Java";
                    c.location = loc(ec);
                    members.add(c);
                    relations.add(contains(typeEntity, c));
                }
            }
        }

        void collectMethod(MethodDeclaration md, Entity typeEntity) {
            boolean isCtor = md.isConstructor();
            List<String> paramTypes = new ArrayList<>();
            for (Object o : md.parameters()) {
                paramTypes.add(typeToString(((SingleVariableDeclaration) o).getType()));
            }
            String ret = isCtor ? "void" : typeToString(md.getReturnType2());
            String sig = JvmDescriptor.methodDescriptor(paramTypes, ret);
            String mq = isCtor ? typeEntity.qualifiedName + ".<init>" : typeEntity.qualifiedName + "." + md.getName().getIdentifier();
            Entity m = new Entity();
            m.type = isCtor ? EntityType.Constructor : EntityType.Method;
            m.name = md.getName().getIdentifier();
            m.qualifiedName = mq;
            m.signature = sig;
            m.id = DeterministicId.executableEntity("java", mq, sig);
            m.language = "Java";
            m.location = loc(md);
            m.metadata.put("constructor", isCtor);
            boolean variadic = !md.parameters().isEmpty()
                    && ((SingleVariableDeclaration) md.parameters().get(md.parameters().size() - 1)).isVarargs();
            if (variadic) {
                m.metadata.put("varargs", true);
            }
            collectModifiers(md.modifiers(), m);
            docComment(m, md);
            members.add(m);
            projectIds.add(m.id);
            relations.add(contains(typeEntity, m));

            int i = 1;
            for (Object o : md.parameters()) {
                SingleVariableDeclaration p = (SingleVariableDeclaration) o;
                Entity param = new Entity();
                param.id = DeterministicId.parameter(m.id, i);
                param.type = EntityType.Parameter;
                param.name = p.getName().getIdentifier();
                param.qualifiedName = mq + "." + p.getName().getIdentifier();
                param.language = "Java";
                param.location = loc(p);
                param.typeRef = typeRefFrom(p.getType());
                params.add(param);
                relations.add(contains(m, param));
                i++;
            }

            // 方法体关系
            Block body = md.getBody();
            if (body != null) {
                body.accept(new BodyRelVisitor(this, m, typeEntity));
            }
        }

        // ---------- 关系构建 ----------

        Entity findType(String qname) {
            for (Entity t : types) {
                if (t.qualifiedName.equals(qname)) {
                    return t;
                }
            }
            return null;
        }

        Entity findByQname(String qname) {
            for (Entity t : types) {
                if (t.qualifiedName.equals(qname)) {
                    return t;
                }
            }
            for (Entity m : members) {
                if (m.qualifiedName.equals(qname)) {
                    return m;
                }
            }
            return null;
        }

        Entity findMethod(String ownerQname, String simple) {
            for (Entity m : members) {
                if ((m.type == EntityType.Method || m.type == EntityType.Constructor)
                        && m.name.equals(simple) && m.qualifiedName.startsWith(ownerQname + ".")) {
                    return m;
                }
            }
            return null;
        }

        boolean isKnownField(String fieldQname) {
            for (Entity m : members) {
                if (m.type == EntityType.Field && m.qualifiedName.equals(fieldQname)) {
                    return true;
                }
            }
            return false;
        }

        /** 关系目标是否为项目内 ID */
        String internalId(String id) {
            return projectIds.contains(id) ? id : null;
        }

        String qualifyAgainstOwner(String typeName, String ownerQname) {
            if (typeName.contains(".")) {
                return typeName;
            }
            int idx = ownerQname.lastIndexOf('.');
            String pkg = idx >= 0 ? ownerQname.substring(0, idx) : "";
            return pkg.isEmpty() ? typeName : pkg + "." + typeName;
        }

        TypeRef typeRefFrom(Type type) {
            TypeRef tr = new TypeRef();
            String t = typeToString(type);
            if (PRIMITIVES.contains(t)) {
                tr.kind = "primitive";
                tr.name = t;
                return tr;
            }
            tr.kind = "class";
            tr.name = t;
            return tr; // external/entityId 在 registerInto 阶段结合 projectTypes 判定
        }

        void classifyTypeRef(TypeRef tr) {
            if (tr == null || "primitive".equals(tr.kind)) {
                return;
            }
            if (projectTypes.contains(tr.name)) {
                String id = DeterministicId.typeEntity("java", tr.name);
                if (projectIds.contains(id)) {
                    tr.entityId = id;
                    tr.external = false;
                    return;
                }
            }
            tr.external = true;
            tr.entityId = null;
        }

        void registerInto(ParseResult out, Set<String> allProjectTypes, Set<String> allProjectIds) {
            // 先分类 typeRef（需全项目类型集合）
            for (Entity t : types) {
                classifyTypeRef(t.typeRef);
            }
            for (Entity m : members) {
                classifyTypeRef(m.typeRef);
            }
            for (Entity p : params) {
                classifyTypeRef(p.typeRef);
            }
            out.entities.add(fileEntity);
            out.entities.addAll(types);
            out.entities.addAll(members);
            out.entities.addAll(params);
            // symbolTable
            for (Entity t : types) {
                out.symbolTable.putIfAbsent(t.qualifiedName, t.id);
            }
            for (Entity m : members) {
                if (m.signature != null) {
                    out.symbolTable.putIfAbsent(m.qualifiedName + "#" + m.signature, m.id);
                }
            }
            // fileIndex
            List<String> idx = out.fileIndex.computeIfAbsent(sf.path, k -> new ArrayList<>());
            idx.add(fileEntity.id);
            for (Entity t : types) {
                idx.add(t.id);
            }
            for (Entity m : members) {
                idx.add(m.id);
            }
            for (Entity p : params) {
                idx.add(p.id);
            }
        }

        // ---------- 位置 / 修饰符 / 注释 ----------

        SourceLocation loc(ASTNode node) {
            if (node == null) {
                return new SourceLocation(sf.path, 1, 1, 1, 1);
            }
            return loc(node.getStartPosition(), node.getStartPosition() + node.getLength());
        }

        SourceLocation loc(int start, int end) {
            int sl = Math.max(1, cu.getLineNumber(start));
            int sc = Math.max(1, cu.getColumnNumber(start));
            int el = Math.max(sl, cu.getLineNumber(Math.max(start, end)));
            int ec = Math.max(sc, cu.getColumnNumber(Math.max(start, end)));
            return new SourceLocation(sf.path, sl, sc, el + 1, ec);
        }

        void collectModifiers(List<?> mods, Entity e) {
            List<String> m = new ArrayList<>();
            for (Object o : mods) {
                if (o instanceof Modifier mod) {
                    m.add(mod.getKeyword().toString());
                }
            }
            if (!m.isEmpty()) {
                e.modifiers = m;
            }
        }

        void docComment(Entity e, BodyDeclaration decl) {
            Javadoc jd = decl.getJavadoc();
            if (jd != null) {
                String text = jd.toString().trim();
                e.docComment = text.isEmpty() ? null : text;
            }
        }

        /** 按具体子类型取修饰符，避免 AbstractTypeDeclaration.modifiers() 的 JLS2 限制。 */
        @SuppressWarnings("unchecked")
        List<?> modifiersOf(AbstractTypeDeclaration type) {
            if (type instanceof TypeDeclaration td) {
                return td.modifiers();
            } else if (type instanceof EnumDeclaration ed) {
                return ed.modifiers();
            } else if (type instanceof AnnotationTypeDeclaration ad) {
                return ad.modifiers();
            } else if (type instanceof RecordDeclaration rd) {
                return rd.modifiers();
            }
            return List.of();
        }

        static Relation contains(Entity parent, Entity child) {
            Relation r = new Relation();
            r.type = RelationType.CONTAINS;
            r.sourceId = parent.id;
            r.targetId = child.id;
            r.location = child.location;
            r.id = DeterministicId.relation(parent.id, "CONTAINS", child.id, child.location, 0);
            return r;
        }

        static Relation dependsOn(Entity from, String extTarget, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.DEPENDS_ON;
            r.sourceId = from.id;
            r.targetId = null;
            r.metadata.put("externalTarget", extTarget);
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "DEPENDS_ON", null, loc, 0);
            return r;
        }

        Relation call(Entity from, Entity target, String extTarget, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.CALLS;
            r.sourceId = from.id;
            r.targetId = internalId(target == null ? null : target.id);
            if (r.targetId == null) {
                r.metadata.put("externalTarget", extTarget == null ? "[external]." + (target == null ? "?" : target.name) : extTarget);
            }
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "CALLS", r.targetId, loc, 0);
            return r;
        }

        Relation instantiate(Entity from, Entity target, String extTarget, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.INSTANTIATES;
            r.sourceId = from.id;
            r.targetId = internalId(target == null ? null : target.id);
            if (r.targetId == null) {
                r.metadata.put("externalTarget", extTarget == null ? "[external]" : extTarget);
            }
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "INSTANTIATES", r.targetId, loc, 0);
            return r;
        }

        Relation throws_(Entity from, Entity target, String ext, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.THROWS;
            r.sourceId = from.id;
            r.targetId = internalId(target == null ? null : target.id);
            if (r.targetId == null) {
                r.metadata.put("externalTarget", ext == null ? "[external]" : ext);
            }
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "THROWS", r.targetId, loc, 0);
            return r;
        }

        Relation fieldAccess(Entity from, Entity typeOwner, String fieldName, boolean write, SourceLocation loc) {
            String fq = typeOwner.qualifiedName + "." + fieldName;
            String fieldId = DeterministicId.fieldEntity("java", fq);
            Relation r = new Relation();
            r.type = write ? RelationType.WRITES : RelationType.READS;
            r.sourceId = from.id;
            r.targetId = isKnownField(fq) ? fieldId : null;
            if (r.targetId == null) {
                r.metadata.put("externalTarget", fq);
            }
            r.location = loc;
            r.id = DeterministicId.relation(from.id, r.type.name(), r.targetId, loc, 0);
            return r;
        }

        /** 方法体关系遍历器 */
        class BodyRelVisitor extends ASTVisitor {
            final FileUnit u;
            final Entity method;
            final Entity ownerType;

            BodyRelVisitor(FileUnit u, Entity method, Entity ownerType) {
                this.u = u;
                this.method = method;
                this.ownerType = ownerType;
            }

            @Override
            public boolean visit(MethodInvocation inv) {
                String simple = inv.getName().getIdentifier();
                Entity target = u.findMethod(ownerType.qualifiedName, simple);
                u.relations.add(u.call(method, target, null, u.loc(inv)));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation cic) {
                String typeName = typeToString(cic.getType());
                String qn = u.qualifyAgainstOwner(typeName, ownerType.qualifiedName);
                Entity target = u.findByQname(qn);
                u.relations.add(u.instantiate(method, target, qn, u.loc(cic)));
                return true;
            }

            @Override
            public boolean visit(Assignment assignment) {
                if (assignment.getLeftHandSide() instanceof FieldAccess fa) {
                    u.relations.add(u.fieldAccess(method, ownerType, fa.getName().getIdentifier(), true, u.loc(assignment)));
                } else if (assignment.getLeftHandSide() instanceof SimpleName sn) {
                    u.relations.add(u.fieldAccess(method, ownerType, sn.getIdentifier(), true, u.loc(assignment)));
                }
                return true;
            }

            @Override
            public boolean visit(FieldAccess fa) {
                if (fa.getExpression() instanceof ThisExpression) {
                    u.relations.add(u.fieldAccess(method, ownerType, fa.getName().getIdentifier(), false, u.loc(fa)));
                }
                return true;
            }

            @Override
            public boolean visit(ThrowStatement stmt) {
                if (stmt.getExpression() instanceof ClassInstanceCreation cic) {
                    String typeName = typeToString(cic.getType());
                    String qn = u.qualifyAgainstOwner(typeName, ownerType.qualifiedName);
                    Entity target = u.findByQname(qn);
                    u.relations.add(u.throws_(method, target, qn, u.loc(stmt)));
                }
                return true;
            }
        }
    }

    // ------------ 静态辅助 ------------

    static List<BodyDeclaration> bodyDecls(AbstractTypeDeclaration t) {
        if (t instanceof TypeDeclaration td) {
            List<BodyDeclaration> out = new ArrayList<>();
            for (Object o : td.bodyDeclarations()) {
                out.add((BodyDeclaration) o);
            }
            return out;
        }
        if (t instanceof RecordDeclaration rd) {
            List<BodyDeclaration> out = new ArrayList<>();
            for (Object o : rd.bodyDeclarations()) {
                out.add((BodyDeclaration) o);
            }
            return out;
        }
        return new ArrayList<>();
    }

    static String typeToString(Type type) {
        if (type == null) {
            return "void";
        }
        if (type.isArrayType()) {
            return typeToString(((ArrayType) type).getElementType()) + "[]";
        }
        if (type.isPrimitiveType() || type.isSimpleType() || type.isQualifiedType()) {
            return type.toString();
        }
        if (type.isParameterizedType()) {
            return typeToString(((ParameterizedType) type).getType());
        }
        if (type.isWildcardType()) {
            return "java.lang.Object";
        }
        return type.toString();
    }

    static String fileName(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}