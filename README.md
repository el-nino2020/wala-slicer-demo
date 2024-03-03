# wala-slicer-demo

[WALA](https://github.com/wala/WALA)

## 说明

https://github.com/el-nino2020/wala-joana-demo

这是别人已有的一个 wala slicer 的例子。我怀疑 `DataDependenceOptions` 根本不可能选择分析 heap，因为跑不出来。
而用了 `no_heap` 参数，就可以立刻跑出来。。。

## 环境
- JDK 11
- Maven 3.8.4

## 参数例子

```shell
java PDFSlice -appJar .\src\main\resources\javaslicer-bench2-inter-procedural-1.0.0.jar -mainClass LBench -srcCaller main -srcCallee println -dd no_heap -cd full -dir backward -outputFile output.txt -printSlice true -jUnitEntry false -junitVersion 3 -targetPackageName com.fasterxml.jackson.core.util -targetSimpleClassName TestTextBuffer -targetMethodName testExpand -useExclusionFile true
```

### `-jUnitEntry`

- 默认就是 `false`，不写也行
- 如果设置为 `true`，则：
  - `-appJar` 应该设置为 classpath
  - `mainClass` 此时无用
  - `junitVersion`: `4` or `3`
    - 如果是 `3`， 需要指定 `-targetPackageName`、 `-targetSimpleClassName` 和 `-targetMethodName`
    - `4` 的话不需要


### `-dd` 参数：

```java
public enum DataDependenceOptions {
    FULL("full", false, false, false, false),
    NO_BASE_PTRS("no_base_ptrs", true, false, false, false),
    NO_BASE_NO_HEAP("no_base_no_heap", true, true, false, false),
    NO_BASE_NO_EXCEPTIONS("no_base_no_exceptions", true, false, false, true),
    NO_BASE_NO_HEAP_NO_EXCEPTIONS("no_base_no_heap_no_exceptions", true, true, false, true),
    NO_HEAP("no_heap", false, true, false, false),
    NO_HEAP_NO_EXCEPTIONS("no_heap_no_exceptions", false, true, false, true),
    NO_EXCEPTIONS("no_exceptions", false, false, false, true),
    /**
     * Note that other code in the slicer checks for the NONE case explicitly, so its effect is not
     * entirely captured by the {@code is*()} methods in {@link DataDependenceOptions}
     */
    NONE("none", true, true, true, true),
    REFLECTION("no_base_no_heap_no_cast", true, true, true, true);
}
```

### `-cd` 参数：
```java
public enum ControlDependenceOptions {
    /** track all control dependencies */
    FULL("full", false, false),

    /**
     * track no control dependencies. Note that other code in the slicer checks for the NONE case
     * explicitly, so its effect is not entirely captured by the {@code is*()} methods in {@link
     * ControlDependenceOptions}
     */
    NONE("none", true, true),

    /** don't track control dependence due to exceptional control flow */
    NO_EXCEPTIONAL_EDGES("no_exceptional_edges", true, false),

    /** don't track control dependence from caller to callee */
    NO_INTERPROC_EDGES("no_interproc_edges", false, true),

    /** don't track interprocedural or exceptional control dependence */
    NO_INTERPROC_NO_EXCEPTION("no_interproc_no_exception", true, true);
}
```

## 打包
```shell
mvn -Dmaven.test.skip=true clean package
```

## 运行
```shell
java -cp ./target/wala-slicer-demo-1.0-SNAPSHOT-jar-with-dependencies.jar PDFSlice 参数列表
```