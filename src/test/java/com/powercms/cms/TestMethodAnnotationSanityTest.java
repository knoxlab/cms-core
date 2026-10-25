package com.powercms.cms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试自身的**执行性**护栏。
 *
 * <p><b>为什么需要这条护栏</b>：我给方法写了 {@code @DisplayName} 却**漏了执行注解**，
 * 于是 JUnit 根本不执行它，而 {@code mvn test} 依然 <b>BUILD SUCCESS</b> ——
 * "测试通过"实际是"测试没跑"。这个错误在本会话里**发生了两次**
 * （一次 3 个用例、一次 8 个用例），两次都是靠"新增用例后总数没变"才发现的。
 *
 * <p><b>为什么用反射而不是扫源码</b>：源码扫描要自己解析注解块，
 * 而"前一行是 @Test"这种近似判断会**误判**（我第一次修的时候就因此把
 * {@code @Test} 加重复了，编译才报错）。反射读的是**编译产物上的真实注解**，
 * 与 JUnit 看到的是同一份事实。
 *
 * <p>判定规则：**带 {@code @DisplayName} 的方法必须是可执行的测试方法**
 * （{@code @Test} / {@code @ParameterizedTest} / {@code @RepeatedTest} /
 * {@code @TestFactory} 之一，或所属类带 {@code @TestInstance} 之外的参数化标记）。
 * 目前本仓库的测试方法一律用 {@code @Test}，因此先按 {@code @Test} 判定；
 * 若将来引入参数化测试，本类会失败并提示**把新注解加进白名单** ——
 * 那正是我们想要的"变更必须被显式确认"。
 */
class TestMethodAnnotationSanityTest {

    /** 允许的执行注解（全限定名）。新增测试风格时必须显式加入这里。 */
    private static final List<String> EXECUTION_ANNOTATIONS = List.of(
        "org.junit.jupiter.api.Test",
        "org.junit.jupiter.params.ParameterizedTest",
        "org.junit.jupiter.api.RepeatedTest",
        "org.junit.jupiter.api.TestFactory",
        "org.junit.jupiter.api.TestTemplate");

    @Test
    @DisplayName("带 @DisplayName 的方法必须真的会被执行（防止「测试没跑却显示成功」）")
    void displayNamedMethodsMustBeExecutable() throws Exception {
        List<Path> roots = testClassRoots();
        assertThat(roots)
            .as("找不到测试类输出目录 —— 本护栏会因此静默失效（变成空断言），"
                + "而那正是它要防的问题")
            .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path root : roots) {
            for (String className : classNamesUnder(root)) {
                Class<?> cls;
                try {
                    cls = Class.forName(className, false, getClass().getClassLoader());
                } catch (Throwable ignored) {
                    continue; // 加载不了的类（缺可选依赖等）不是本护栏的对象
                }
                if (!cls.getName().endsWith("Test")) continue;
                scanned++;
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getAnnotation(DisplayName.class) == null) continue;
                    if (!hasExecutionAnnotation(m)) {
                        offenders.add(cls.getSimpleName() + "#" + m.getName());
                    }
                }
            }
        }

        assertThat(scanned)
            .as("没有扫描到任何测试类 —— 护栏形同虚设")
            .isGreaterThan(0);
        assertThat(offenders)
            .as("这些方法带了 @DisplayName 但没有执行注解：它们**永远不会运行**，"
                + "而 mvn test 仍会显示成功。请补上 @Test（或把新的执行注解加入白名单）")
            .isEmpty();
    }

    private static boolean hasExecutionAnnotation(Method m) {
        for (var ann : m.getAnnotations()) {
            if (EXECUTION_ANNOTATIONS.contains(ann.annotationType().getName())) return true;
        }
        return false;
    }

    /** 测试类输出根目录（surefire 实际运行的那份 classpath 目录）。 */
    private static List<Path> testClassRoots() {
        List<Path> roots = new ArrayList<>();
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path p = Path.of(entry);
            if (!Files.isDirectory(p)) continue;
            // 只挑含测试类输出特征的目录，避免扫描整条 classpath（含依赖 jar）
            if (Files.isDirectory(p.resolve("com/powercms")) || Files.isDirectory(p.resolve("com"))) {
                roots.add(p);
            }
        }
        return roots;
    }

    /** 目录下所有 {@code *Test.class} 的全限定名（不含内部类）。 */
    private static List<String> classNamesUnder(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith("Test.class")) continue;
                // 跳过内部类（Outer$Inner.class），它们不是独立测试类
                if (name.contains("$")) continue;
                String rel = root.relativize(p).toString()
                    .replace(java.io.File.separatorChar, '.');
                out.add(rel.substring(0, rel.length() - ".class".length()));
            }
        }
        return out;
    }
}
