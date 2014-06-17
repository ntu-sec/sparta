import org.checkerframework.framework.qual.FromByteCode;

import sparta.checkers.quals.Source;
import sparta.checkers.quals.Sink;

import static sparta.checkers.quals.FlowPermission.*;
//warning: FlowPolicy: Found transitive flow
@FromByteCode
//:: error: (forbidden.flow)   
class TestImplicitConstructor { }

class TestNoParamConstructor {
    @FromByteCode
    //:: error: (forbidden.flow)   
    TestNoParamConstructor() { }
}
@FromByteCode
class TestParamConstructor {

    @FromByteCode
  //:: error: (forbidden.flow)   
    TestParamConstructor(String name) { }
    @FromByteCode
    //:: error: (forbidden.flow)
    static void test(String test) { }
}

class TestExplicitConstructorType {
     @Source(INTERNET) @Sink(CAMERA)
     TestExplicitConstructorType()  { }
}

@Source(INTERNET) @Sink(CAMERA)
class TestClassAnnotationType {
    TestClassAnnotationType()  { }
}


class ConstructorAnnotation {

    void testConstructor() {
        
        // Sanity check -- OK
        //:: error: (argument.type.incompatible)
        TestParamConstructor.test("test");
        // OK -- Conservative flow on parameter
        //:: error: (argument.type.incompatible)  :: error: (forbidden.flow)
        new TestParamConstructor("hello");
        
        @Source(INTERNET) @Sink(CAMERA) TestClassAnnotationType classAnnotation = new TestClassAnnotationType();
        
        @Source(INTERNET) @Sink(CAMERA) TestExplicitConstructorType constructorAnnotation = new TestExplicitConstructorType();
        
        // Conservative flow return types.
        
        // BUG? This should be thrown //:: error: (assignment.type.incompatible) 

        //:: error: (forbidden.flow) :: error: (forbidden.flow)
        TestImplicitConstructor imp = new TestImplicitConstructor();
        // BUG? This should be thrown //:: error: (assignment.type.incompatible)
        //:: error: (forbidden.flow) :: error: (forbidden.flow)
        TestNoParamConstructor noParam = new TestNoParamConstructor();

    }
}
