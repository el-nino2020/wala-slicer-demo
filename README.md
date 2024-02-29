# wala-slicer-demo

[WALA](https://github.com/wala/WALA)

参数例子：

```shell
java PDFSlice -appJar .\src\main\resources\javaslicer-bench2-inter-procedural-1.0.0.jar -mainClass LBench -srcCaller main -srcCallee println -dd no_heap -cd full -dir backward -outputFile output.txt -printSlice true

```

打包：
```shell
mvn -Dmaven.test.skip=true clean package
```

运行：
```shell
java -cp ./target/wala-slicer-demo-1.0-SNAPSHOT.jar PDFSlice 参数列表
```