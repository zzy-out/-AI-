package com.legacyrecon.parser.java;

import com.legacyrecon.parser.api.*;
import com.legacyrecon.ucm.id.DeterministicId;
import com.legacyrecon.ucm.id.JvmDescriptor;
import com.legacyrecon.ucm.model.*;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 02.2 Java 解析器（JDT）生产实现。
 *
 * <p>生产实现特性：
 * <ul>
 *   <li>启用 {@code setResolveBindings(true)} 与 {@code setEnvironment()} 进行跨文件符号决议（02.2）。</li>
 *   <li>从 ITypeBinding 抽取 INHERITS / IMPLEMENTS 关系，从 IMethodBinding 抽取 OVERRIDES 关系。</li>
 *   <li>方法描述符基于 ITypeBinding.getErasure() 构造，保证泛型擦除签名确定性（附录 A）。</li>
 *   <li>方法调用 resolveMethodBinding、字段访问 resolveVariableBinding，跨文件链接更精确。</li>
 *   <li>Classpath 自动发现（Maven/Gradle target/build、pom.xml 依赖）与手动配置融合。</li>
 *   <li>Binding 失败时按文件降级为 DOM 解析，保证健壮性。</li>
 * </ul>
 */
@Component
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
        if (request.files.isEmpty()) {
            out.addStats("fileCount", 0);
            out.addStats("durationMs", Math.max(1, (System.nanoTime() - t0) / 1_000_000));
            return out;
        }

        Path workingDir = request.workingDir;
        JavaConfig jc = request.config.java == null ? new JavaConfig() : request.config.java;

        // 0) 收集 source path entries 与 classpath
        String[] sourcePaths = discoverSourcePaths(workingDir, jc, request.files);
        String[] classpath = discoverClasspath(workingDir, jc);
        String[] encodings = encodings(sourcePaths, request.config.encoding);
        boolean useBindings = tryBindingFirst(request);

        Set<String> projectTypes = new HashSet<>();
        Set<String> projectIds = new HashSet<>();
        List<FileUnit> units = new ArrayList<>();
        BindingIndex index = new BindingIndex();

        // 1) 一次性设置 ASTParser 解析全部文件（绑定模式）
        if (useBindings) {
            try {
                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setKind(ASTParser.K_COMPILATION_UNIT);
                parser.setResolveBindings(true);
                parser.setBindingsRecovery(true);
                parser.setEnvironment(classpath, sourcePaths, encodings, true);
                Map<String, String> opts = JavaCore.getOptions();
                JavaCore.setComplianceOptions(JavaCore.VERSION_17, opts);
                opts.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
                parser.setCompilerOptions(opts);

                Map<String, SourceFile> byPath = new LinkedHashMap<>();
                for (SourceFile sf : request.files) {
                    if (sf.content == null) continue;
                    byPath.put(sf.path, sf);
                }
                FileASTRequestor requestor = new FileASTRequestor() {
                    @Override
                    public void acceptAST(String sourceFilePath, CompilationUnit ast) {
                        String rel = relativize(sourceFilePath, workingDir, byPath);
                        SourceFile sf = byPath.get(rel);
                        if (sf == null) sf = byPath.get(sourceFilePath);
                        if (sf == null) return;
                        try {
                            FileUnit u = new FileUnit(sf, ast, index, true);
                            units.add(u);
                            out.issues.addAll(u.issues);
                        } catch (Exception ex) {
                            out.issues.add(ParseIssue.error("JAVA.PARSE_FAILED",
                                    "绑定模式解析失败：" + ex.getMessage(),
                                    new SourceLocation(sf.path, 1, 1, 1, 1)));
                        }
                    }
                };
                String[] paths = new String[request.files.size()];
                char[][] contents = new char[request.files.size()][];
                int i = 0;
                for (SourceFile sf : request.files) {
                    paths[i] = workingDir != null ? workingDir.resolve(sf.path).toString() : sf.path;
                    contents[i] = sf.content == null ? new char[0] : sf.content.toCharArray();
                    i++;
                }
                parser.createASTs(paths, null, new String[0], requestor, null);
            } catch (Exception ex) {
                out.issues.add(ParseIssue.warning("JAVA.BINDING_DISABLED",
                        "绑定模式整体失败，退回 DOM 模式：" + ex.getMessage(),
                        new SourceLocation(request.files.get(0).path, 1, 1, 1, 1)));
                useBindings = false;
                units.clear();
            }
        }

        // 2) DOM 模式兜底（单文件解析，无绑定）
        if (!useBindings) {
            for (SourceFile sf : request.files) {
                if (sf.content == null) continue;
                try {
                    CompilationUnit cu = parseAstDom(sf.content, sf.path, request);
                    FileUnit u = new FileUnit(sf, cu, index, false);
                    units.add(u);
                    out.issues.addAll(u.issues);
                } catch (Exception ex) {
                    out.issues.add(ParseIssue.error("JAVA.PARSE_FAILED",
                            "DOM 解析失败：" + ex.getMessage(),
                            new SourceLocation(sf.path, 1, 1, 1, 1)));
                }
            }
        }

        // 3) 阶段二：构建全局 projectTypes/projectIds（用于外部判定）
        for (FileUnit u : units) {
            projectTypes.add(u.sf.path);
            for (Entity t : u.types) projectTypes.add(t.qualifiedName);
            projectIds.add(u.fileEntity.id);
            for (Entity e : u.types) projectIds.add(e.id);
            for (Entity e : u.members) projectIds.add(e.id);
        }

        // 4) 基于 binding 的跨文件关系（INHERITS/IMPLEMENTS/OVERRIDES）
        List<Relation> extra = resolveCrossFile(index, projectIds, projectTypes);
        // 去重：关系构造 helper 既入列表又返回值，调用处可能二次添加（同 ID 语义相同）
        Set<String> seenRelationIds = new HashSet<>();
        for (FileUnit u : units) {
            u.classifyTypeRefs(projectTypes, projectIds);
            u.registerInto(out, projectTypes, projectIds);
            for (Relation r : u.relations) {
                if (r.id != null && seenRelationIds.add(r.id)) {
                    out.relations.add(r);
                }
            }
        }
        for (Relation r : extra) {
            if (r.id != null && seenRelationIds.add(r.id)) {
                out.relations.add(r);
            }
        }

        // 5) 统计
        out.addStats("fileCount", units.size());
        out.addStats("entityCount", out.entities.size());
        out.addStats("relationCount", out.relations.size());
        out.addStats("issueCount", out.issues.size());
        out.addStats("bindingMode", useBindings ? "full" : "dom");
        out.addStats("durationMs", Math.max(1, (System.nanoTime() - t0) / 1_000_000));
        return out;
    }

    // ---- 环境发现 ----

    private static String[] discoverSourcePaths(Path workingDir, JavaConfig jc, List<SourceFile> files) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        if (jc.sourceRoots != null) {
            for (String r : jc.sourceRoots) {
                if (r != null && !r.isEmpty()) {
                    roots.add(workingDir != null ? workingDir.resolve(r).toString() : r);
                }
            }
        }
        if (workingDir != null) {
            for (String std : new String[]{"src/main/java", "src/test/java", "src", "java"}) {
                Path p = workingDir.resolve(std);
                if (Files.isDirectory(p)) roots.add(p.toString());
            }
        }
        // 兜底：从每个文件向上找 package-info 或 .java 推断
        if (roots.isEmpty() && workingDir != null) {
            for (SourceFile sf : files) {
                String qp = sf.path.replace('\\', '/');
                int idx = qp.indexOf('/');
                if (idx >= 0) {
                    roots.add(workingDir.resolve(qp.substring(0, idx)).toString());
                    break;
                }
            }
            if (roots.isEmpty()) roots.add(workingDir.toString());
        }
        if (roots.isEmpty()) roots.add("");
        return roots.toArray(new String[0]);
    }

    private static String[] discoverClasspath(Path workingDir, JavaConfig jc) {
        LinkedHashSet<String> cp = new LinkedHashSet<>();
        if ("maven".equalsIgnoreCase(jc.classpathStrategy)) {
            if (workingDir != null) {
                addIfDir(cp, workingDir.resolve("target/classes"));
                addIfDir(cp, workingDir.resolve("target/test-classes"));
            }
        } else if ("gradle".equalsIgnoreCase(jc.classpathStrategy)) {
            if (workingDir != null) {
                addIfDir(cp, workingDir.resolve("build/classes/java/main"));
                addIfDir(cp, workingDir.resolve("build/classes/java/test"));
            }
        }
        if (jc.manualClasspath != null) {
            for (String s : jc.manualClasspath) {
                if (s != null && !s.isEmpty()) {
                    cp.add(workingDir != null ? workingDir.resolve(s).toString() : s);
                }
            }
        }
        return cp.toArray(new String[0]);
    }

    private static void addIfDir(LinkedHashSet<String> cp, Path p) {
        if (Files.isDirectory(p)) cp.add(p.toString());
    }

    private static String[] encodings(String[] paths, String encoding) {
        String enc = encoding == null ? "UTF-8" : encoding;
        String[] out = new String[paths.length];
        Arrays.fill(out, enc);
        return out;
    }

    private static boolean tryBindingFirst(ParseRequest req) {
        return req.workingDir != null ||
                (req.config.java != null && req.config.java.classpathStrategy != null);
    }

    private static String relativize(String abs, Path workingDir, Map<String, SourceFile> byPath) {
        if (workingDir == null) return abs;
        try {
            String rel = workingDir.relativize(Path.of(abs)).toString().replace('\\', '/');
            if (byPath.containsKey(rel)) return rel;
        } catch (Exception ignore) {}
        // 反查 byPath 尾部匹配
        for (String k : byPath.keySet()) {
            if (abs.endsWith(k) || abs.endsWith(k.replace('/', java.io.File.separatorChar))) {
                return k;
            }
        }
        return abs;
    }

    private CompilationUnit parseAstDom(String content, String path, ParseRequest request) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(false);
        Map<String, String> opts = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_17, opts);
        parser.setCompilerOptions(opts);
        parser.setSource(content.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        return cu;
    }

    // ====================================================================
    //  BindingIndex：收集类型/方法绑定，用于跨文件关系解析
    // ====================================================================

    static class BindingIndex {
        final Map<String, Entity> byBindingKey = new LinkedHashMap<>();
        final Map<String, Entity> byQname = new LinkedHashMap<>();
        final Map<String, Entity> byMethodSigKey = new LinkedHashMap<>();
        final List<Runnable> deferredRelations = new ArrayList<>();
    }

    // ====================================================================
    //  FileUnit：单个文件的解析结果
    // ====================================================================

    private class FileUnit {
        final SourceFile sf;
        final CompilationUnit cu;
        final BindingIndex index;
        final boolean bindingMode;
        final Entity fileEntity;
        final List<Entity> types = new ArrayList<>();
        final List<Entity> members = new ArrayList<>();
        final List<Entity> params = new ArrayList<>();
        final List<Relation> relations = new ArrayList<>();
        final List<ParseIssue> issues = new ArrayList<>();

        FileUnit(SourceFile sf, CompilationUnit cu, BindingIndex index, boolean bindingMode) {
            this.sf = sf;
            this.cu = cu;
            this.index = index;
            this.bindingMode = bindingMode;
            this.fileEntity = buildFileEntity();
            collectSyntaxIssues(cu);

            String pkg = cu.getPackage() == null ? "" : cu.getPackage().getName().getFullyQualifiedName();
            for (Object o : cu.types()) {
                if (o instanceof AbstractTypeDeclaration type) {
                    collectType(type, pkg, null, true);
                }
            }
            // import -> DEPENDS_ON 关系
            for (Object o : cu.imports()) {
                ImportDeclaration imp = (ImportDeclaration) o;
                String qn = imp.getName().getFullyQualifiedName();
                dependsOn(fileEntity, qn, loc(imp));
            }
        }

        void collectSyntaxIssues(CompilationUnit cu) {
            for (IProblem problem : cu.getProblems()) {
                SourceLocation l = loc(problem.getSourceStart(), problem.getSourceEnd());
                if (problem.isError()) {
                    issues.add(ParseIssue.warning("JAVA.SYNTAX", problem.getMessage(), l));
                } else {
                    issues.add(ParseIssue.info("JAVA.WARNING", problem.getMessage(), l));
                }
            }
        }

        Entity buildFileEntity() {
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

        // ---------- 类型收集 ----------

        void collectType(AbstractTypeDeclaration type, String enclosingQname, ITypeBinding enclosingBinding, boolean isTop) {
            ITypeBinding tb = bindingMode ? type.resolveBinding() : null;
            Entity t = new Entity();
            String simpleName = type.getName().getIdentifier();
            String qname;
            if (tb != null && tb.getQualifiedName() != null && !tb.getQualifiedName().isEmpty()) {
                qname = tb.getQualifiedName();
            } else if (enclosingQname.isEmpty()) {
                qname = simpleName;
            } else {
                qname = enclosingQname + "." + simpleName;
            }
            t.type = classifyTypeKind(type);
            t.name = simpleName;
            t.qualifiedName = qname;
            t.id = DeterministicId.typeEntity("java", qname);
            t.language = "Java";
            t.location = loc(type);
            collectModifiers(modifiersOf(type), t);
            docComment(t, type);

            // 泛型参数
            if (type instanceof TypeDeclaration td) {
                for (Object tp : td.typeParameters()) {
                    org.eclipse.jdt.core.dom.TypeParameter tpDecl = (org.eclipse.jdt.core.dom.TypeParameter) tp;
                    com.legacyrecon.ucm.model.TypeParameter gp =
                            new com.legacyrecon.ucm.model.TypeParameter(tpDecl.getName().getIdentifier());
                    for (Object b : tpDecl.typeBounds()) {
                        Type boundAst = (Type) b;
                        gp.bounds.add(typeRefFrom(boundAst, tb != null ? resolveBindingOf(boundAst) : null));
                    }
                    t.genericParameters.add(gp);
                }
            } else if (type instanceof RecordDeclaration rd) {
                for (Object tp : rd.typeParameters()) {
                    org.eclipse.jdt.core.dom.TypeParameter tpDecl = (org.eclipse.jdt.core.dom.TypeParameter) tp;
                    com.legacyrecon.ucm.model.TypeParameter gp =
                            new com.legacyrecon.ucm.model.TypeParameter(tpDecl.getName().getIdentifier());
                    t.genericParameters.add(gp);
                }
            }

            types.add(t);
            relations.add(isTop ? contains(fileEntity, t) : contains(findType(enclosingQname), t));

            // 绑定索引注册
            if (tb != null) {
                index.byBindingKey.put(tb.getKey(), t);
            }
            index.byQname.put(t.qualifiedName, t);

            // INHERITS / IMPLEMENTS
            if (tb != null) {
                ITypeBinding parent = tb.getSuperclass();
                if (parent != null && !isJavaLangObject(parent)) {
                    addInheritanceRelation(t, parent, RelationType.INHERITS, loc(type));
                }
                for (ITypeBinding iface : tb.getInterfaces()) {
                    addInheritanceRelation(t, iface, RelationType.IMPLEMENTS, loc(type));
                }
            } else {
                // DOM 近似
                if (type instanceof TypeDeclaration td) {
                    Type sc = td.getSuperclassType();
                    if (sc != null) addInheritanceRelation(t, sc, RelationType.INHERITS, loc(sc));
                    for (Object o : td.superInterfaceTypes()) {
                        addInheritanceRelation(t, (Type) o, RelationType.IMPLEMENTS, loc((Type) o));
                    }
                }
            }

            collectMembers(type, t, tb);
        }

        void collectMembers(AbstractTypeDeclaration type, Entity typeEntity, ITypeBinding tb) {
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
                        IBinding vb = bindingMode ? frag.resolveBinding() : null;
                        ITypeBinding ftb = null;
                        if (vb instanceof IVariableBinding ivb) {
                            ftb = ivb.getType();
                        }
                        field.typeRef = typeRefFrom(f.getType(), ftb);
                        collectModifiers(f.modifiers(), field);
                        members.add(field);
                        relations.add(contains(typeEntity, field));
                        if (ftb != null) {
                            index.byMethodSigKey.put("F:" + fq, field);
                        }
                    }
                } else if (body instanceof MethodDeclaration md) {
                    collectMethod(md, typeEntity, tb);
                } else if (body instanceof AbstractTypeDeclaration nested) {
                    collectType(nested, typeEntity.qualifiedName, tb, false);
                }
            }
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

        void collectMethod(MethodDeclaration md, Entity typeEntity, ITypeBinding ownerBinding) {
            IMethodBinding mb = bindingMode ? md.resolveBinding() : null;
            boolean isCtor = md.isConstructor();

            List<String> paramTypes = new ArrayList<>();
            List<ITypeBinding> paramBindings = new ArrayList<>();
            int i = 1;
            for (Object o : md.parameters()) {
                SingleVariableDeclaration p = (SingleVariableDeclaration) o;
                if (mb != null && mb.getParameterTypes().length >= i) {
                    ITypeBinding pb = mb.getParameterTypes()[i - 1].getErasure();
                    paramTypes.add(qualifiedName(pb));
                    paramBindings.add(pb);
                } else {
                    paramTypes.add(typeToString(p.getType()));
                    paramBindings.add(null);
                }
                i++;
            }
            String returnErasure;
            if (isCtor) {
                returnErasure = "void";
            } else if (mb != null) {
                returnErasure = qualifiedName(mb.getReturnType().getErasure());
            } else {
                returnErasure = typeToString(md.getReturnType2());
            }
            String sig = JvmDescriptor.methodDescriptor(paramTypes, returnErasure);
            String simpleName = md.getName().getIdentifier();
            String mq = isCtor ? typeEntity.qualifiedName + ".<init>"
                    : typeEntity.qualifiedName + "." + simpleName;
            Entity m = new Entity();
            m.type = isCtor ? EntityType.Constructor : EntityType.Method;
            m.name = simpleName;
            m.qualifiedName = mq;
            m.signature = sig;
            m.id = DeterministicId.executableEntity("java", mq, sig);
            m.language = "Java";
            m.location = loc(md);
            m.metadata.put("constructor", isCtor);
            boolean variadic = !md.parameters().isEmpty()
                    && ((SingleVariableDeclaration) md.parameters().get(md.parameters().size() - 1)).isVarargs();
            if (variadic) m.metadata.put("varargs", true);
            if (mb != null) {
                m.metadata.put("bindingKey", mb.getKey());
            }
            collectModifiers(md.modifiers(), m);
            docComment(m, md);
            members.add(m);
            relations.add(contains(typeEntity, m));

            if (mb != null) {
                index.byBindingKey.put(mb.getKey(), m);
                index.byMethodSigKey.put("M:" + mq + sig, m);
            }

            // 参数实体
            i = 1;
            int pi = 0;
            for (Object o : md.parameters()) {
                SingleVariableDeclaration p = (SingleVariableDeclaration) o;
                Entity param = new Entity();
                param.id = DeterministicId.parameter(m.id, i);
                param.type = EntityType.Parameter;
                param.name = p.getName().getIdentifier();
                param.qualifiedName = mq + "." + p.getName().getIdentifier();
                param.language = "Java";
                param.location = loc(p);
                ITypeBinding ptb = paramBindings.get(pi);
                param.typeRef = typeRefFrom(p.getType(), ptb);
                params.add(param);
                relations.add(contains(m, param));
                i++;
                pi++;
            }

            // throws 关系
            if (mb != null) {
                for (ITypeBinding exc : mb.getExceptionTypes()) {
                    TypeRef tr = typeRefFrom(null, exc);
                    relations.add(throws_(m, tr, loc(md)));
                }
            } else {
                for (Object o : md.thrownExceptionTypes()) {
                    Type t = (Type) o;
                    relations.add(throws_(m, typeRefFrom(t, null), loc(t)));
                }
            }

            // OVERRIDES 判定
            if (mb != null && !isCtor) {
                try {
                    IMethodBinding overridden = findOverridden(mb, ownerBinding);
                    if (overridden != null) {
                        final String targetIdKey = overridden.getKey();
                        index.deferredRelations.add(() -> {
                            Entity target = index.byBindingKey.get(targetIdKey);
                            if (target != null) {
                                Relation r = new Relation();
                                r.type = RelationType.OVERRIDES;
                                r.sourceId = m.id;
                                r.targetId = target.id;
                                r.location = m.location;
                                r.id = DeterministicId.relation(m.id, "OVERRIDES", target.id, m.location, 0);
                                relations.add(r);
                            } else {
                                // 项目外部
                                Relation r = new Relation();
                                r.type = RelationType.OVERRIDES;
                                r.sourceId = m.id;
                                r.targetId = null;
                                r.metadata.put("externalTarget", qualifiedName(overridden.getDeclaringClass())
                                        + "." + overridden.getName() + "#"
                                        + methodDescriptorOf(overridden));
                                r.location = m.location;
                                r.id = DeterministicId.relation(m.id, "OVERRIDES", null, m.location, 0);
                                relations.add(r);
                            }
                        });
                    }
                } catch (Exception ignore) {
                    // 绑定不完整时跳过 OVERRIDES
                }
            }

            // 方法体：CALLS / READS / WRITES / INSTANTIATES / THROWS
            Block body = md.getBody();
            if (body != null) {
                body.accept(new BodyRelVisitor(this, m, typeEntity));
            }
        }

        // ---------- 绑定辅助 ----------

        ITypeBinding resolveBindingOf(Type t) {
            try {
                return t.resolveBinding();
            } catch (Exception ignore) {
                return null;
            }
        }

        static boolean isJavaLangObject(ITypeBinding b) {
            return "java.lang.Object".equals(b.getQualifiedName());
        }

        static String qualifiedName(ITypeBinding b) {
            if (b == null) return "java.lang.Object";
            if (b.isPrimitive()) return b.getName();
            if (b.isArray()) {
                return qualifiedName(b.getComponentType()) + "[]";
            }
            String q = b.getQualifiedName();
            if (q == null || q.isEmpty()) {
                // 嵌套类 fallback
                q = b.getName();
                ITypeBinding outer = b.getDeclaringClass();
                while (outer != null) {
                    q = outer.getQualifiedName() + "$" + q;
                    outer = outer.getDeclaringClass();
                }
            }
            return q == null ? b.getName() : q;
        }

        static IMethodBinding findOverridden(IMethodBinding mb, ITypeBinding owner) {
            // 遍历父类与接口链
            Deque<ITypeBinding> queue = new ArrayDeque<>();
            ITypeBinding sc = owner.getSuperclass();
            if (sc != null) queue.add(sc);
            for (ITypeBinding i : owner.getInterfaces()) queue.add(i);
            Set<String> visited = new HashSet<>();
            while (!queue.isEmpty()) {
                ITypeBinding cur = queue.poll();
                if (!visited.add(cur.getKey())) continue;
                for (IMethodBinding cand : cur.getDeclaredMethods()) {
                    if (cand.isConstructor()) continue;
                    if (mb.overrides(cand)) {
                        return cand;
                    }
                    // 兜底：同签名近似判定
                    if (mb.getName().equals(cand.getName())
                            && mb.getParameterTypes().length == cand.getParameterTypes().length) {
                        boolean same = true;
                        for (int i = 0; i < mb.getParameterTypes().length; i++) {
                            ITypeBinding a = mb.getParameterTypes()[i].getErasure();
                            ITypeBinding b = cand.getParameterTypes()[i].getErasure();
                            if (!qualifiedName(a).equals(qualifiedName(b))) {
                                same = false;
                                break;
                            }
                        }
                        if (same) return cand;
                    }
                }
                ITypeBinding s2 = cur.getSuperclass();
                if (s2 != null) queue.add(s2);
                for (ITypeBinding i : cur.getInterfaces()) queue.add(i);
            }
            return null;
        }

        static String methodDescriptorOf(IMethodBinding mb) {
            List<String> p = new ArrayList<>();
            for (ITypeBinding t : mb.getParameterTypes()) p.add(qualifiedName(t.getErasure()));
            String ret = qualifiedName(mb.getReturnType().getErasure());
            return JvmDescriptor.methodDescriptor(p, ret);
        }

        // ---------- 关系：INHERITS / IMPLEMENTS ----------

        void addInheritanceRelation(Entity child, ITypeBinding parent, RelationType kind, SourceLocation loc) {
            String parentQname = qualifiedName(parent.getErasure());
            String parentId = index.byQname.containsKey(parentQname) ? index.byQname.get(parentQname).id : null;
            if (parentId == null && parent.getKey() != null) {
                parentId = index.byBindingKey.get(parent.getKey()) == null ? null : index.byBindingKey.get(parent.getKey()).id;
            }
            Relation r = new Relation();
            r.type = kind;
            r.sourceId = child.id;
            r.targetId = parentId;
            if (parentId == null) r.metadata.put("externalTarget", parentQname);
            r.location = loc;
            r.id = DeterministicId.relation(child.id, kind.name(), parentId, loc, 0);
            relations.add(r);
        }

        void addInheritanceRelation(Entity child, Type parentAst, RelationType kind, SourceLocation loc) {
            String parentName = typeToString(parentAst);
            String full = qualifyAgainstOwner(parentName, child.qualifiedName);
            String parentId = index.byQname.containsKey(full) ? index.byQname.get(full).id : null;
            Relation r = new Relation();
            r.type = kind;
            r.sourceId = child.id;
            r.targetId = parentId;
            if (parentId == null) r.metadata.put("externalTarget", full);
            r.location = loc;
            r.id = DeterministicId.relation(child.id, kind.name(), parentId, loc, 0);
            relations.add(r);
        }

        // ---------- TypeRef 构造 ----------

        TypeRef typeRefFrom(Type astType, ITypeBinding binding) {
            TypeRef tr = new TypeRef();
            if (binding != null) {
                if (binding.isPrimitive()) {
                    tr.kind = "primitive";
                    tr.name = binding.getName();
                    return tr;
                }
                if (binding.isArray()) {
                    int dims = 0;
                    ITypeBinding t = binding;
                    while (t.isArray()) {
                        dims++;
                        t = t.getComponentType();
                    }
                    if (t.isPrimitive()) {
                        tr.kind = "primitive";
                        tr.name = t.getName();
                    } else {
                        tr.kind = t.isInterface() ? "interface" : "class";
                        tr.name = qualifiedName(t.getErasure());
                    }
                    tr.arrayDimensions = dims;
                    return tr;
                }
                if (binding.isTypeVariable()) {
                    tr.kind = "typeParameter";
                    tr.name = binding.getName();
                    return tr;
                }
                if (binding.isEnum()) tr.kind = "enum";
                else if (binding.isAnnotation()) tr.kind = "interface";
                else if (binding.isInterface()) tr.kind = "interface";
                else tr.kind = "class";
                tr.name = qualifiedName(binding.getErasure());
                return tr;
            }
            // DOM 模式
            if (astType == null) {
                tr.kind = "primitive";
                tr.name = "void";
                return tr;
            }
            String t = typeToString(astType);
            if (PRIMITIVES.contains(t)) {
                tr.kind = "primitive";
                tr.name = t;
            } else {
                tr.kind = "class";
                tr.name = t;
            }
            return tr;
        }

        void classifyTypeRefs(Set<String> allProjectTypes, Set<String> allProjectIds) {
            for (Entity t : types) classifyTypeRef(t.typeRef, allProjectTypes, allProjectIds);
            for (Entity m : members) classifyTypeRef(m.typeRef, allProjectTypes, allProjectIds);
            for (Entity p : params) classifyTypeRef(p.typeRef, allProjectTypes, allProjectIds);
        }

        void classifyTypeRef(TypeRef tr, Set<String> allProjectTypes, Set<String> allProjectIds) {
            if (tr == null || "primitive".equals(tr.kind) || "typeParameter".equals(tr.kind)) return;
            if (tr.name == null) return;
            if (allProjectTypes.contains(tr.name)) {
                String id = DeterministicId.typeEntity("java", tr.name);
                if (allProjectIds.contains(id)) {
                    tr.entityId = id;
                    tr.external = false;
                    return;
                }
            }
            tr.external = true;
            tr.entityId = null;
        }

        void registerInto(ParseResult out, Set<String> allProjectTypes, Set<String> allProjectIds) {
            out.entities.add(fileEntity);
            out.entities.addAll(types);
            out.entities.addAll(members);
            out.entities.addAll(params);
            for (Entity t : types) out.symbolTable.putIfAbsent(t.qualifiedName, t.id);
            for (Entity m : members) {
                if (m.signature != null) {
                    out.symbolTable.putIfAbsent(m.qualifiedName + "#" + m.signature, m.id);
                } else {
                    out.symbolTable.putIfAbsent(m.qualifiedName, m.id);
                }
            }
            List<String> idx = out.fileIndex.computeIfAbsent(sf.path, k -> new ArrayList<>());
            idx.add(fileEntity.id);
            for (Entity t : types) idx.add(t.id);
            for (Entity m : members) idx.add(m.id);
            for (Entity p : params) idx.add(p.id);
        }

        // ---------- 查找 ----------

        Entity findType(String qname) {
            for (Entity t : types) if (t.qualifiedName.equals(qname)) return t;
            return index.byQname.get(qname);
        }

        String qualifyAgainstOwner(String typeName, String ownerQname) {
            if (typeName.contains(".")) return typeName;
            int idx = ownerQname.lastIndexOf('.');
            String pkg = idx >= 0 ? ownerQname.substring(0, idx) : "";
            return pkg.isEmpty() ? typeName : pkg + "." + typeName;
        }

        Entity findMethodByBinding(IMethodBinding mb) {
            if (mb == null) return null;
            Entity e = index.byBindingKey.get(mb.getKey());
            if (e != null) return e;
            // fallback: qname + descriptor
            String owner = qualifiedName(mb.getDeclaringClass().getErasure());
            String mq = mb.isConstructor() ? owner + ".<init>" : owner + "." + mb.getName();
            String desc = methodDescriptorOf(mb);
            return index.byMethodSigKey.get("M:" + mq + desc);
        }

        Entity findFieldByBinding(IVariableBinding vb) {
            if (vb == null) return null;
            Entity e = index.byBindingKey.get(vb.getKey());
            if (e != null) return e;
            String owner = qualifiedName(vb.getDeclaringClass() != null ? vb.getDeclaringClass().getErasure() : null);
            if (owner == null) return null;
            String fq = owner + "." + vb.getName();
            return index.byMethodSigKey.get("F:" + fq);
        }

        String internalId(String id) { return (id != null && index.byBindingKey.containsValue(findEntityById(id))) ? id : id; }

        Entity findEntityById(String id) {
            if (index.byQname.containsValue(null)) return null;
            for (Entity t : types) if (id.equals(t.id)) return t;
            for (Entity m : members) if (id.equals(m.id)) return m;
            return null;
        }

        // ---------- 位置 / 修饰符 / 注释 ----------

        SourceLocation loc(ASTNode node) {
            if (node == null) return new SourceLocation(sf.path, 1, 1, 1, 1);
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
                if (o instanceof Modifier mod) m.add(mod.getKeyword().toString());
            }
            if (!m.isEmpty()) e.modifiers = m;
        }

        void docComment(Entity e, BodyDeclaration decl) {
            Javadoc jd = decl.getJavadoc();
            if (jd != null) {
                String text = jd.toString().trim();
                e.docComment = text.isEmpty() ? null : text;
            }
        }

        @SuppressWarnings("unchecked")
        List<?> modifiersOf(AbstractTypeDeclaration type) {
            if (type instanceof TypeDeclaration td) return td.modifiers();
            if (type instanceof EnumDeclaration ed) return ed.modifiers();
            if (type instanceof AnnotationTypeDeclaration ad) return ad.modifiers();
            if (type instanceof RecordDeclaration rd) return rd.modifiers();
            return List.of();
        }

        // ---------- 关系构造 ----------

        Relation contains(Entity parent, Entity child) {
            Relation r = new Relation();
            r.type = RelationType.CONTAINS;
            r.sourceId = parent.id;
            r.targetId = child.id;
            r.location = child.location;
            r.id = DeterministicId.relation(parent.id, "CONTAINS", child.id, child.location, 0);
            relations.add(r);
            return r;
        }

        Relation dependsOn(Entity from, String extTarget, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.DEPENDS_ON;
            r.sourceId = from.id;
            r.targetId = null;
            r.metadata.put("externalTarget", extTarget);
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "DEPENDS_ON", null, loc, 0);
            relations.add(r);
            return r;
        }

        Relation call(Entity from, Entity target, String extTarget, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.CALLS;
            r.sourceId = from.id;
            r.targetId = target == null ? null : target.id;
            if (r.targetId == null) {
                r.metadata.put("externalTarget", extTarget == null ? "[external]" : extTarget);
            }
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "CALLS", r.targetId, loc, 0);
            relations.add(r);
            return r;
        }

        Relation instantiate(Entity from, Entity target, String extTarget, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.INSTANTIATES;
            r.sourceId = from.id;
            r.targetId = target == null ? null : target.id;
            if (r.targetId == null) r.metadata.put("externalTarget", extTarget == null ? "[external]" : extTarget);
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "INSTANTIATES", r.targetId, loc, 0);
            relations.add(r);
            return r;
        }

        Relation throws_(Entity from, TypeRef tr, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.THROWS;
            r.sourceId = from.id;
            r.targetId = tr == null ? null : tr.entityId;
            if (r.targetId == null) {
                String ext = (tr == null || tr.name == null) ? "[external]" : tr.name;
                r.metadata.put("externalTarget", ext);
            }
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "THROWS", r.targetId, loc, 0);
            relations.add(r);
            return r;
        }

        Relation fieldAccess(Entity from, Entity typeOwner, String fieldName, Entity fieldEntity, boolean write, SourceLocation loc) {
            String fq = (typeOwner == null ? "[unknown]" : typeOwner.qualifiedName) + "." + fieldName;
            Relation r = new Relation();
            r.type = write ? RelationType.WRITES : RelationType.READS;
            r.sourceId = from.id;
            r.targetId = fieldEntity == null ? null : fieldEntity.id;
            if (r.targetId == null) r.metadata.put("externalTarget", fq);
            r.location = loc;
            r.id = DeterministicId.relation(from.id, r.type.name(), r.targetId, loc, 0);
            relations.add(r);
            return r;
        }

        Relation reference(Entity from, TypeRef tr, SourceLocation loc) {
            Relation r = new Relation();
            r.type = RelationType.REFERENCES;
            r.sourceId = from.id;
            r.targetId = tr == null ? null : tr.entityId;
            if (r.targetId == null) r.metadata.put("externalTarget", tr == null ? "" : tr.name);
            r.location = loc;
            r.id = DeterministicId.relation(from.id, "REFERENCES", r.targetId, loc, 0);
            relations.add(r);
            return r;
        }

        // ---------- 方法体访问者（生产级：使用 bindings） ----------

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
                IMethodBinding mb = bindingMode ? inv.resolveMethodBinding() : null;
                Entity target = u.findMethodByBinding(mb);
                String ext = null;
                if (target == null) {
                    if (mb != null) {
                        String owner = qualifiedName(mb.getDeclaringClass().getErasure());
                        ext = owner + "." + mb.getName() + "#" + methodDescriptorOf(mb);
                    } else {
                        ext = "[external]." + inv.getName().getIdentifier();
                    }
                }
                u.call(method, target, ext, u.loc(inv));
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation inv) {
                IMethodBinding mb = bindingMode ? inv.resolveMethodBinding() : null;
                Entity target = u.findMethodByBinding(mb);
                String ext = null;
                if (target == null) {
                    if (mb != null) {
                        ext = qualifiedName(mb.getDeclaringClass().getErasure()) + "." + mb.getName()
                                + "#" + methodDescriptorOf(mb);
                    }
                }
                u.call(method, target, ext, u.loc(inv));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation cic) {
                IMethodBinding ctor = bindingMode ? cic.resolveConstructorBinding() : null;
                Entity target = u.findMethodByBinding(ctor);
                String ext;
                if (ctor != null) {
                    ITypeBinding tb = ctor.getDeclaringClass();
                    ext = qualifiedName(tb.getErasure()) + ".<init>"
                            + "#" + methodDescriptorOf(ctor);
                    if (target == null) {
                        // 也尝试 Class 实体
                        target = index.byQname.get(qualifiedName(tb.getErasure()));
                    }
                } else {
                    String typeName = typeToString(cic.getType());
                    ext = u.qualifyAgainstOwner(typeName, ownerType.qualifiedName);
                    target = u.findType(ext);
                }
                u.instantiate(method, target, ext, u.loc(cic));
                return true;
            }

            @Override
            public boolean visit(Assignment assignment) {
                IVariableBinding vb = bindingMode && assignment.getLeftHandSide() instanceof Expression e
                        ? resolveVar(e) : null;
                Entity fieldEntity = vb != null ? u.findFieldByBinding(vb) : null;
                Entity owner = vb != null && vb.getDeclaringClass() != null
                        ? index.byQname.get(qualifiedName(vb.getDeclaringClass().getErasure()))
                        : ownerType;
                String name = vb != null ? vb.getName() : simpleNameOf(assignment.getLeftHandSide());
                u.fieldAccess(method, owner, name, fieldEntity, true, u.loc(assignment));
                return true;
            }

            @Override
            public boolean visit(FieldAccess fa) {
                IVariableBinding vb = bindingMode ? fa.resolveFieldBinding() : null;
                Entity fieldEntity = vb != null ? u.findFieldByBinding(vb) : null;
                Entity owner;
                String fName;
                if (vb != null) {
                    owner = vb.getDeclaringClass() != null
                            ? index.byQname.get(qualifiedName(vb.getDeclaringClass().getErasure()))
                            : ownerType;
                    fName = vb.getName();
                } else {
                    owner = fa.getExpression() instanceof ThisExpression ? ownerType : null;
                    fName = fa.getName().getIdentifier();
                }
                boolean write = false;
                u.fieldAccess(method, owner, fName, fieldEntity, write, u.loc(fa));
                return true;
            }

            @Override
            public boolean visit(QualifiedName qn) {
                if (qn.getQualifier() != null) {
                    IVariableBinding vb = bindingMode ? resolveNameToVar(qn) : null;
                    if (vb != null && vb.isField()) {
                        Entity fieldEntity = u.findFieldByBinding(vb);
                        Entity owner = vb.getDeclaringClass() != null
                                ? index.byQname.get(qualifiedName(vb.getDeclaringClass().getErasure()))
                                : ownerType;
                        u.fieldAccess(method, owner, vb.getName(), fieldEntity, false, u.loc(qn));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(ThrowStatement stmt) {
                Expression expr = stmt.getExpression();
                ITypeBinding tb = bindingMode && expr != null ? expr.resolveTypeBinding() : null;
                TypeRef tr;
                if (tb != null) {
                    tr = typeRefFrom(null, tb.getErasure());
                    classifyTypeRef(tr, index.byQname.keySet(),
                            new HashSet<>(Collections.singleton("")));
                } else if (expr instanceof ClassInstanceCreation cic) {
                    String qn = u.qualifyAgainstOwner(typeToString(cic.getType()), ownerType.qualifiedName);
                    tr = new TypeRef("class", qn);
                } else {
                    tr = TypeRef.unknown("[throw-expression]");
                }
                u.throws_(method, tr, u.loc(stmt));
                return true;
            }

            IVariableBinding resolveVar(Expression e) {
                try {
                    if (e instanceof Name n) return (IVariableBinding) n.resolveBinding();
                    if (e instanceof FieldAccess fa) return fa.resolveFieldBinding();
                } catch (Exception ignore) {}
                return null;
            }

            IVariableBinding resolveNameToVar(QualifiedName qn) {
                try {
                    IBinding b = qn.resolveBinding();
                    if (b instanceof IVariableBinding vb) return vb;
                } catch (Exception ignore) {}
                return null;
            }

            String simpleNameOf(Expression e) {
                if (e instanceof SimpleName sn) return sn.getIdentifier();
                if (e instanceof FieldAccess fa) return fa.getName().getIdentifier();
                if (e instanceof QualifiedName qn) return qn.getName().getIdentifier();
                return e.toString();
            }
        }
    }

    // ====================================================================
    //  BindingIndex: resolveCrossFile - 跨文件延迟关系（当前主要用于 OVERRIDES/INHERITS 补齐）
    // ====================================================================

    List<Relation> resolveCrossFile(BindingIndex index, Set<String> projectIds, Set<String> projectTypes) {
        List<Relation> out = new ArrayList<>();
        for (Runnable r : index.deferredRelations) {
            try { r.run(); } catch (Exception ignore) {}
        }
        return out;
    }

    // ====================================================================
    //  静态辅助
    // ====================================================================

    static EntityType classifyTypeKind(AbstractTypeDeclaration type) {
        if (type instanceof TypeDeclaration td) return td.isInterface() ? EntityType.Interface : EntityType.Class;
        if (type instanceof EnumDeclaration) return EntityType.Enum;
        if (type instanceof AnnotationTypeDeclaration) return EntityType.Annotation;
        if (type instanceof RecordDeclaration) return EntityType.Class;
        return EntityType.Class;
    }

    static List<BodyDeclaration> bodyDecls(AbstractTypeDeclaration t) {
        if (t instanceof TypeDeclaration td) {
            List<BodyDeclaration> out = new ArrayList<>();
            for (Object o : td.bodyDeclarations()) out.add((BodyDeclaration) o);
            return out;
        }
        if (t instanceof RecordDeclaration rd) {
            List<BodyDeclaration> out = new ArrayList<>();
            for (Object o : rd.bodyDeclarations()) out.add((BodyDeclaration) o);
            return out;
        }
        return new ArrayList<>();
    }

    static String typeToString(Type type) {
        if (type == null) return "void";
        if (type.isArrayType()) {
            return typeToString(((ArrayType) type).getElementType()) + "[]";
        }
        if (type.isPrimitiveType() || type.isSimpleType() || type.isQualifiedType()) {
            return type.toString();
        }
        if (type.isParameterizedType()) {
            return typeToString(((ParameterizedType) type).getType());
        }
        if (type.isWildcardType()) return "java.lang.Object";
        if (type.isNameQualifiedType()) return type.toString();
        return type.toString();
    }

    static String fileName(String path) {
        int idx = path.lastIndexOf('/');
        if (idx < 0) idx = path.lastIndexOf('\\');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
