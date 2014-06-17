
package sparta.checkers;

import static org.checkerframework.framework.qual.DefaultLocation.LOCAL_VARIABLE;
import static org.checkerframework.framework.qual.DefaultLocation.OTHERWISE;
import static org.checkerframework.framework.qual.DefaultLocation.RECEIVERS;
import static org.checkerframework.framework.qual.DefaultLocation.RESOURCE_VARIABLE;
import static org.checkerframework.framework.qual.DefaultLocation.UPPER_BOUNDS;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.reflection.ReflectionResolutionAnnotatedTypeFactory;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.qual.DefaultLocation;
import org.checkerframework.framework.qual.PolyAll;

import org.checkerframework.framework.type.*;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedIntersectionType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedUnionType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedWildcardType;
import org.checkerframework.framework.util.AnnotationBuilder;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy;
import org.checkerframework.framework.util.QualifierDefaults;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy.MultiGraphFactory;
import org.checkerframework.framework.util.QualifierDefaults.DefaultApplierElement;
import org.checkerframework.framework.util.QualifierPolymorphism;

import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.node.Node;

import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.InternalUtils;
import org.checkerframework.javacutil.TreeUtils;

import java.io.File;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;

import sparta.checkers.quals.FineSink;
import sparta.checkers.quals.FineSource;
import sparta.checkers.quals.FlowPermission;
import sparta.checkers.quals.ParameterizedFlowPermission;
import sparta.checkers.quals.PolyFlow;
import sparta.checkers.quals.PolyFlowReceiver;
import sparta.checkers.quals.PolySink;
import sparta.checkers.quals.PolySource;
import sparta.checkers.quals.Sink;
import sparta.checkers.quals.Source;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
public class FlowAnnotatedTypeFactory extends BaseAnnotatedTypeFactory{

    protected final AnnotationMirror NOSOURCE, ANYSOURCE, POLYSOURCE;
    protected final AnnotationMirror NOSINK, ANYSINK, POLYSINK;
    protected final AnnotationMirror POLYALL;
    protected final AnnotationMirror LITERALSOURCE,  FROMLITERALSINK;
    protected final AnnotationMirror CONDITIONALSINK, FROMCONDITIONALSOURCE;

    protected final AnnotationMirror SOURCE;
    protected final AnnotationMirror SINK;

    protected final FlowPolicy flowPolicy;

    // FlowVisitor uses these to hold flow state
    protected final FlowAnalyzer flowAnalizer;
    
    private final ParameterizedFlowPermission ANY;
    private final ParameterizedFlowPermission LITERAL;
    private final ParameterizedFlowPermission CONDITIONAL;

    public FlowAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);

        ANY = new ParameterizedFlowPermission(FlowPermission.ANY);
        LITERAL = new ParameterizedFlowPermission(FlowPermission.LITERAL);
        CONDITIONAL = new ParameterizedFlowPermission(FlowPermission.CONDITIONAL);
        
        NOSOURCE = createAnnoFromSource(Collections.<ParameterizedFlowPermission> emptySet());
        NOSINK = createAnnoFromSink(Collections.<ParameterizedFlowPermission> emptySet());

        POLYSOURCE = AnnotationUtils.fromClass(elements, PolySource.class);
        POLYSINK = AnnotationUtils.fromClass(elements, PolySink.class);
        POLYALL = AnnotationUtils.fromClass(elements, PolyAll.class);

        ANYSOURCE = createAnnoFromSource(ANY);
        ANYSINK = createAnnoFromSink( ANY);
        
        
        SOURCE = AnnotationUtils.fromClass(elements, Source.class);
        SINK = AnnotationUtils.fromClass(elements, Sink.class);

        sourceValue = TreeUtils
                .getMethod("sparta.checkers.quals.Source", "value", 0, processingEnv);
        sinkValue = TreeUtils.getMethod("sparta.checkers.quals.Sink", "value", 0, processingEnv);

        // Must call super.initChecker before the lint option can be checked.
        final boolean scArg = checker.getLintOption(FlowPolicy.STRICT_CONDITIONALS_OPTION, false);
        final String pfArg = checker.getOption(FlowPolicy.POLICY_FILE_OPTION);
        if (pfArg == null || pfArg.trim().isEmpty()) {
            flowPolicy = new FlowPolicy(new File("flow-policy"),scArg, processingEnv);
        } else {
            flowPolicy = new FlowPolicy(new File(pfArg), scArg, processingEnv);
        }

        
        LITERALSOURCE = createAnnoFromSource(new TreeSet<ParameterizedFlowPermission>(
                Arrays.asList(LITERAL)));

        final Set<ParameterizedFlowPermission> literalSink = new TreeSet<ParameterizedFlowPermission>(
                flowPolicy.getSinkFromSource(LITERAL, true));
        FROMLITERALSINK = createAnnoFromSink(literalSink);

        CONDITIONALSINK = createAnnoFromSink(new TreeSet<ParameterizedFlowPermission>(
                Arrays.asList(CONDITIONAL)));

        final Set<ParameterizedFlowPermission> condtionalSource = new TreeSet<ParameterizedFlowPermission>(
                flowPolicy.getSourceFromSink(CONDITIONAL, true));
        FROMCONDITIONALSOURCE = createAnnoFromSource(condtionalSource);

        flowAnalizer = new FlowAnalyzer(getFlowPolicy());

     // Every subclass must call postInit!
        if (this.getClass().equals(FlowAnnotatedTypeFactory.class)) {
            this.postInit();
        }
    }

    private AnnotationMirror createAnnoFromSink(
			ParameterizedFlowPermission sinks) {
		return createAnnoFromSink(Collections.singleton(sinks));
	}

	private AnnotationMirror createAnnoFromSource(
			ParameterizedFlowPermission source) {
		return createAnnoFromSource(Collections.singleton(source));
	}

	@Override
    public CFTransfer createFlowTransferFunction(
            CFAbstractAnalysis<CFValue, CFStore, CFTransfer> analysis) {
  
        CFTransfer ret = new CFTransfer(analysis){
            /**
             * This method overrides super so that variables are not
             * refined in conditionals see test case in flow/Conditions.java
             */
            @Override
            protected TransferResult<CFValue, CFStore> strengthenAnnotationOfEqualTo(
                    TransferResult<CFValue, CFStore> res,
                    Node firstNode, Node secondNode,
                    CFValue firstValue, CFValue secondValue,
                    boolean notEqualTo) {
                return res;
            }
        };
        return ret;
    }

    @Override
    protected QualifierDefaults createQualifierDefaults() {
        QualifierDefaults defaults =  super.createQualifierDefaults();
        // Use the top type for local variables and let flow refine the type.
        //Upper bounds should be top too.
        DefaultLocation[] topLocations = {LOCAL_VARIABLE,RESOURCE_VARIABLE, UPPER_BOUNDS};

        defaults.addAbsoluteDefaults(ANYSOURCE, topLocations);
        defaults.addAbsoluteDefaults(NOSINK, topLocations);

        //Default for receivers and parameters is (All sources allowed) -> CONDITIONAL
        DefaultLocation[] conditionalSinkLocs = {RECEIVERS, DefaultLocation.PARAMETERS};
        defaults.addAbsoluteDefaults(CONDITIONALSINK, conditionalSinkLocs);
        defaults.addAbsoluteDefaults(FROMCONDITIONALSOURCE, conditionalSinkLocs);


        // Default is LITERAL -> (ALL MAPPED SINKS) for everything else
        defaults.addAbsoluteDefault(FROMLITERALSINK, OTHERWISE);
        defaults.addAbsoluteDefault(LITERALSOURCE, OTHERWISE);

        return defaults;
    }

    public  AnnotationMirror createAnnoFromSink(final Set<ParameterizedFlowPermission> sinks) {
        final AnnotationBuilder builder = new AnnotationBuilder(processingEnv,
                Sink.class);
        
        List<AnnotationMirror> finesinks = new ArrayList<AnnotationMirror>();
        
        for (ParameterizedFlowPermission p : sinks) {
                final AnnotationBuilder builderFine = new AnnotationBuilder(processingEnv, FineSink.class);
                String [] params = p.getParameters().toArray(new String[0]);
                FlowPermission permission = p.getPermission();
                builderFine.setValue("value",permission );
                builderFine.setValue("params", params);
                finesinks.add(builderFine.build());
        }            

        builder.setValue("finesinks", finesinks.toArray(new AnnotationMirror[0]));
        builder.setValue("value", new FlowPermission[0]);
        return builder.build();
    }

    public  AnnotationMirror createAnnoFromSource(Set<ParameterizedFlowPermission> sources) {
        final AnnotationBuilder builder = new AnnotationBuilder(processingEnv,
                Source.class);
              
        List<AnnotationMirror> finesources = new ArrayList<AnnotationMirror>();

        for (ParameterizedFlowPermission p : sources) {
                final AnnotationBuilder builderFine = new AnnotationBuilder(processingEnv, FineSource.class);
                String [] params = p.getParameters().toArray(new String[0]);
                builderFine.setValue("params", params);
                FlowPermission permission = p.getPermission();
                builderFine.setValue("value",permission );
                finesources.add(builderFine.build());
        }          

        builder.setValue("finesources", finesources.toArray(new AnnotationMirror[0]));         
        builder.setValue("value", new FlowPermission[0]);
        return builder.build();
    }

    protected ExecutableElement sourceValue;
    protected ExecutableElement sinkValue;



    @Override
    protected void annotateImplicit(Tree tree, AnnotatedTypeMirror type, boolean useFlow) {
        Element element = InternalUtils.symbol(tree);
        handleDefaulting(element, type);
        super.annotateImplicit(tree, type, useFlow);
    }

    @Override
    public void annotateImplicit(Element element, AnnotatedTypeMirror type) {
        handleDefaulting(element, type);
        super.annotateImplicit(element, type);
    }

    protected void handleDefaulting(final Element element, final AnnotatedTypeMirror type) {
        Element iter = element;
        DefaultApplierElement applier = new DefaultApplierElement(this, element, type);

//        if (iter != null && iter.getKind() == ElementKind.CONSTRUCTOR
//                && type !=null && type.getKind() == TypeKind.DECLARED){
//            //TODO constructor hack
//            Set<FlowPermission> sources = Flow.getSources(type);
//            Set<FlowPermission> sinks = Flow.getSinks(type);
//            AnnotationMirror polysink = type.getAnnotation(PolySink.class);
//            AnnotationMirror polysource = type.getAnnotation(PolySource.class);
//            if(sources.isEmpty() && sinks.isEmpty() && polysink == null && polysource == null){
//                type.addAnnotation(NOSOURCE);
//                type.addAnnotation(ANYSINK);
//            }
//        }
        while (iter != null) {
            if (this.isFromByteCode(iter)) {
                if(iter.getKind() == ElementKind.FIELD){
                    if (ElementUtils.isEffectivelyFinal(iter)){
                        //This is a final field, so it is must be from 
                        // a literal source, so use that default.
                        //This way, all the Android constant don't 
                        //need to be annotated.
                        applier.apply(LITERALSOURCE, DefaultLocation.FIELD);
                        applier.apply(FROMLITERALSINK, DefaultLocation.FIELD);
                    }else{
                        //this is a public non-final field, it could be from any 
                        //source and it could be sent any where.
                        applier.apply(ANYSOURCE, DefaultLocation.FIELD);
                        applier.apply(ANYSINK, DefaultLocation.FIELD);
                    }
                }

                //A parameter of an not reviewed method could go any where
                applier.apply(ANYSINK, DefaultLocation.PARAMETERS);
                //Don't add a source, this way it will be 
                //defaulted based on what is in the flow policy
                //applier.apply(NOSOURCE, DefaultLocation.PARAMETERS);
                
                //A return type could be from any source
                applier.apply(ANYSOURCE, DefaultLocation.RETURNS);             
                //Don't add a sink, this way it will be 
                //defaulted based on what is in the flow policy
                //applier.apply(ANYSINK, DefaultLocation.RETURNS);
                
                //All other types could be from any where or go any where.
                applier.apply(ANYSOURCE, DefaultLocation.OTHERWISE);
                applier.apply(ANYSINK, DefaultLocation.OTHERWISE);

            } else if (this.getDeclAnnotation(iter, PolyFlow.class) != null) {
                // Use poly flow sources and sinks for return types .
                applier.apply(POLYSOURCE, DefaultLocation.RETURNS);
                applier.apply(POLYSINK, DefaultLocation.RETURNS);

                // Use poly flow sources and sinks for Parameter types (This is
                // excluding receivers)
                applier.apply(POLYSINK, DefaultLocation.PARAMETERS);
                applier.apply(POLYSOURCE, DefaultLocation.PARAMETERS);

                return;

            } else if (this.getDeclAnnotation(iter, PolyFlowReceiver.class) != null) {
                // Use poly flow sources and sinks for return types .
                applier.apply(POLYSOURCE, DefaultLocation.RETURNS);
                applier.apply(POLYSINK, DefaultLocation.RETURNS);

                // Use poly flow sources and sinks for Parameter types (This is
                // excluding receivers)
                applier.apply(POLYSINK, DefaultLocation.PARAMETERS);
                applier.apply(POLYSOURCE, DefaultLocation.PARAMETERS);

                // Use poly flow sources and sinks for receiver types
                applier.apply(POLYSINK, DefaultLocation.RECEIVERS);
                applier.apply(POLYSOURCE, DefaultLocation.RECEIVERS);

                return;
            }

            if (iter instanceof PackageElement) {
                iter = ElementUtils.parentPackage(this.elements, (PackageElement) iter);
            } else {
                iter = iter.getEnclosingElement();
            }
        }
    }



    @Override
    protected ListTreeAnnotator createTreeAnnotator() {

        ImplicitsTreeAnnotator implicits = new ImplicitsTreeAnnotator(this);
        // But let's send null down any sink and give it no sources.
        implicits.addTreeKind(Tree.Kind.NULL_LITERAL, ANYSINK);
        implicits.addTreeKind(Tree.Kind.NULL_LITERAL, NOSOURCE);

        // Literals, other than null are different too
        // There are no Byte or Short literal types in java (0b is treated as an
        // int),
        // so there does not need to be a mapping for them here.
        implicits.addTreeKind(Tree.Kind.INT_LITERAL, LITERALSOURCE);
        implicits.addTreeKind(Tree.Kind.LONG_LITERAL, LITERALSOURCE);
        implicits.addTreeKind(Tree.Kind.FLOAT_LITERAL, LITERALSOURCE);
        implicits.addTreeKind(Tree.Kind.DOUBLE_LITERAL, LITERALSOURCE);
        implicits.addTreeKind(Tree.Kind.BOOLEAN_LITERAL, LITERALSOURCE);
        implicits.addTreeKind(Tree.Kind.CHAR_LITERAL, LITERALSOURCE);
        implicits.addTreeKind(Tree.Kind.STRING_LITERAL, LITERALSOURCE);

        implicits.addTreeKind(Tree.Kind.INT_LITERAL, FROMLITERALSINK);
        implicits.addTreeKind(Tree.Kind.LONG_LITERAL, FROMLITERALSINK);
        implicits.addTreeKind(Tree.Kind.FLOAT_LITERAL, FROMLITERALSINK);
        implicits.addTreeKind(Tree.Kind.DOUBLE_LITERAL, FROMLITERALSINK);
        implicits.addTreeKind(Tree.Kind.BOOLEAN_LITERAL, FROMLITERALSINK);
        implicits.addTreeKind(Tree.Kind.CHAR_LITERAL, FROMLITERALSINK);
        implicits.addTreeKind(Tree.Kind.STRING_LITERAL, FROMLITERALSINK);

        return new ListTreeAnnotator(
                new FlowPolicyTreeAnnotator(this),
                new PropagationTreeAnnotator(this),
                implicits
        );

    }

    protected class FlowPolicyTreeAnnotator extends TreeAnnotator {

        public FlowPolicyTreeAnnotator(FlowAnnotatedTypeFactory atypeFactory) {
            super(atypeFactory);
        }

        @Override
        public Void defaultAction(Tree tree, AnnotatedTypeMirror type) {
            completePolicyFlows(type);
            return super.defaultAction(tree, type);
        }
        @Override
        public Void visitNewClass(NewClassTree node, AnnotatedTypeMirror p) {
            //This is a horrible hack around the bad implementation of constructor results
            //(CF treats annotations on constructor results in stub files as if it were a 
            //default and therefore ignores it. 
            //This hack makes it impossible to write any annotation in the following location:
            //new @A SomeClass();
            //Doing so will cause extra annotations in on the constructor results and therefore 
            //type.invalid warning
            p.addAnnotations(atypeFactory.constructorFromUse(node).first.getReturnType().getAnnotations());
            return null;
        }
   }

    @Override
    protected TypeAnnotator createTypeAnnotator() {
        return new FlowPolicyTypeAnnotator(this);
    }

    /**
     * If a type only contains a flow source or flow sink, the other qualifier is
     * filled in with the most general possible information that is consistent
     * with the policy file.
     * @author smillst
     *
     */
    class FlowPolicyTypeAnnotator extends TypeAnnotator {

        // FlowChecker checker;
        public FlowPolicyTypeAnnotator(FlowAnnotatedTypeFactory factory) {
            super(factory);
        }
        @Override
        public Void visitArray(AnnotatedArrayType type, Void p) {
            completePolicyFlows(type);
            return super.visitArray(type, p);
        }
        @Override
        public Void visitDeclared(AnnotatedDeclaredType type, Void p) {
            completePolicyFlows(type);
            return super.visitDeclared(type, p);
        }
        @Override
        public Void visitExecutable(AnnotatedExecutableType t, Void elem) {
            completePolicyFlows(t);

            //Don't call super because it skips the receiver type for some reason
            scan(t.getReturnType(), elem);
            scanAndReduce(t.getReceiverType(), elem, null);
            scanAndReduce(t.getParameterTypes(), elem, null);
            scanAndReduce(t.getThrownTypes(), elem, null);
            scanAndReduce(t.getTypeVariables(), elem, null);
            return null;
        }
        @Override
        public Void visitIntersection(AnnotatedIntersectionType type, Void p) {
            completePolicyFlows(type);
            return super.visitIntersection(type, p);
        }

        @Override
        public Void visitPrimitive(AnnotatedPrimitiveType type, Void p) {
            completePolicyFlows(type);
            return super.visitPrimitive(type, p);
        }
        @Override
        public Void visitTypeVariable(AnnotatedTypeVariable type, Void p) {
            //Calling type.getEffectiveAnnotations() expands
            //the upper bounds causing an infinite loop for types like
            // E extends Enum<E>
            //So visit call super, visit the extends
            //and then complete the policy flow
            Void r = super.visitTypeVariable(type, p);
            completePolicyFlows(type);
            return r;
        }
        @Override
        public Void visitUnion(AnnotatedUnionType type, Void p) {
            completePolicyFlows(type);
            return super.visitUnion(type, p);
        }
        @Override
        public Void visitWildcard(AnnotatedWildcardType type, Void p) {
            //Calling type.getEffectiveAnnotations() expands
            //the upper bounds causing an infinite loop for types like
            // ? extends Enum<?>
            //So visit call super, visit the extends
            //and then complete the policy flow
            Void r =  super.visitWildcard(type, p);
            completePolicyFlows(type);
            return r;
        }

    }


    protected void completePolicyFlows(final AnnotatedTypeMirror type) {
        Set<ParameterizedFlowPermission> sources = Collections.<ParameterizedFlowPermission> emptySet();
        Set<ParameterizedFlowPermission> sinks = Collections.<ParameterizedFlowPermission> emptySet();
        if ((type instanceof AnnotatedTypeVariable)) {
            if (shouldNotComplete(type.getAnnotations())) {
                return;
            }
            for (AnnotationMirror anno : type.getAnnotations()) {
                if (AnnotationUtils.areSameByClass(anno, Source.class)) {
                    sources = Flow.getSources(anno);
                } else if (AnnotationUtils.areSameByClass(anno, Sink.class)) {
                    sinks = Flow.getSinks(anno);
                }
            }
        } else {
            if (shouldNotComplete(type.getEffectiveAnnotations())) {
                return;
            }
            sources = Flow.getSources(type);
            sinks = Flow.getSinks(type);
        }

        AnnotationMirror newAnno;
        if (!sources.isEmpty()) {
            Set<ParameterizedFlowPermission> newSink = getFlowPolicy().getIntersectionAllowedSinks(sources);
            newAnno=  createAnnoFromSink(newSink);
            type.replaceAnnotation(newAnno);
        } else if (!sinks.isEmpty()) {
            Set<ParameterizedFlowPermission> newSource = getFlowPolicy().getIntersectionAllowedSources(sinks);
            newAnno=  createAnnoFromSource(newSource);
            type.replaceAnnotation(newAnno);
        }

    }

    /**
     * Only complete flows if one of the annotations is missing. (Do not
     * complete flows if the source or sinks is {}.  Defaulting or the user
     * may add @Source({}) and @Sink({}).)
     *
     * Also don't complete if this is a void type
     * @param set
     * @return
     */
    private static boolean shouldNotComplete(Set<AnnotationMirror> set) {
      //  if (type.getKind() == TypeKind.VOID) return true;
        boolean hasSource = false;
        boolean hasSink = false;
       for(AnnotationMirror anno : set){
           if(AnnotationUtils.areSameByClass(anno, Source.class)){
               hasSource = true;
           }else if (AnnotationUtils.areSameByClass(anno, Sink.class)){
               hasSink = true;
           }else if (AnnotationUtils.areSameByClass(anno, PolySource.class)){
               hasSource = true;
           }else if (AnnotationUtils.areSameByClass(anno, PolySink.class)){
               hasSink = true;
           }
       }
        return (hasSink == hasSource);
    }



    @Override
    protected MultiGraphQualifierHierarchy.MultiGraphFactory createQualifierHierarchyFactory() {
        return new MultiGraphQualifierHierarchy.MultiGraphFactory(this);
    }

    @Override
    public QualifierHierarchy createQualifierHierarchy(MultiGraphFactory factory) {
        return new FlowQualifierHierarchy(factory);
    }

    protected class FlowQualifierHierarchy extends MultiGraphQualifierHierarchy {

        protected FlowQualifierHierarchy(MultiGraphFactory f) {
            super(f);
        }

        @Override
        protected Set<AnnotationMirror> findBottoms(
                Map<AnnotationMirror, Set<AnnotationMirror>> supertypes) {
            Set<AnnotationMirror> newbottoms = AnnotationUtils.createAnnotationSet();
            newbottoms.add(NOSOURCE);
            newbottoms.add(ANYSINK);
            return newbottoms;
        }

        @Override
        protected Set<AnnotationMirror> findTops(
                Map<AnnotationMirror, Set<AnnotationMirror>> supertypes) {
            Set<AnnotationMirror> newtops = AnnotationUtils.createAnnotationSet();
            newtops.add(ANYSOURCE);
            newtops.add(NOSINK);
            return newtops;
        }


        private boolean isSourceQualifier(AnnotationMirror anno) {
            return AnnotationUtils.areSameByClass(anno, Source.class)
                    || isPolySourceQualifier(anno);
        }

        private boolean isPolySourceQualifier(AnnotationMirror anno) {
            return AnnotationUtils.areSameByClass(anno, PolySource.class)
                    || AnnotationUtils.areSameByClass(anno, PolyAll.class);
        }

        private boolean isSinkQualifier(AnnotationMirror anno) {
            return isPolySinkQualifier(anno) || AnnotationUtils.areSameByClass(anno, Sink.class);
        }

        private boolean isPolySinkQualifier(AnnotationMirror anno) {
            return AnnotationUtils.areSameByClass(anno, PolySink.class)
                    || AnnotationUtils.areSameByClass(anno, PolyAll.class);
        }

        @Override
        public AnnotationMirror getTopAnnotation(AnnotationMirror start) {
            if (isSourceQualifier(start)) {
                return ANYSOURCE;
            } else if (isSinkQualifier(start)) {
                return NOSINK;
            } else if (QualifierPolymorphism.isPolyAll(start)) {
                return POLYALL;
            } else {
                checker.errorAbort("FlowChecker: unexpected AnnotationMirror: " + start);
                return null; // dead code
            }
        }

        @Override
        public boolean isSubtype(Collection<? extends AnnotationMirror> rhs,
                Collection<? extends AnnotationMirror> lhs) {
            if (rhs.isEmpty() ^ lhs.isEmpty()) {
                // TODO: more general fix
                // This happens when casting:
                /**
                 * class TypeAsKeyHashMap<T> { 
                 *    public <S extends T> S get(T type) 
                 *    { return (S) type; } 
                 * }
                 */

                // super will give this error
                // error: MultiGraphQualifierHierarchy: empty annotations in
                // lhs: or rhs:

                return false;
            }
            return super.isSubtype(rhs, lhs);
        }


        @Override
        public boolean isSubtype(AnnotationMirror subtype, AnnotationMirror supertype){
            if (isPolySourceQualifier(supertype) && isPolySourceQualifier(subtype)) {
                return true;
            } else if (isPolySourceQualifier(supertype) && isSourceQualifier(subtype)) {
                // If super is poly, only bottom is a subtype
                return Flow.getSources(subtype).isEmpty();
            } else if (isSourceQualifier(supertype) && isPolySourceQualifier(subtype)) {
                // if sub is poly, only top is a supertype
                return Flow.getSources(supertype).contains(ANY);
            } else if (isSourceQualifier(supertype) && isSourceQualifier(subtype)) {
                // Check the set
                Set<ParameterizedFlowPermission> superset = Flow.getSources(supertype);
                Set<ParameterizedFlowPermission> subset = Flow.getSources(subtype);
                return isSuperSet(superset, subset);
            } else if (isPolySinkQualifier(supertype) && isPolySinkQualifier(subtype)) {
                return true;
            } else if (isPolySinkQualifier(supertype) && isSinkQualifier(subtype)) {
                // If super is poly, only bottom is a subtype
                return Flow.getSinks(subtype).contains(ANY);
            } else if (isSinkQualifier(supertype) && isPolySinkQualifier(subtype)) {
                // if sub is poly, only top is a supertype
                return Flow.getSinks(supertype).isEmpty();
            } else if (isSinkQualifier(supertype) && isSinkQualifier(subtype)) {
                // Check the set (sinks are backward)
                Set<ParameterizedFlowPermission> subset = Flow.getSinks(supertype);
                Set<ParameterizedFlowPermission> superset = Flow.getSinks(subtype);
                return isSuperSet(superset, subset);
            } else {
                // annotations should either both be sources or sinks.
                return false;
            }
        }
        
        private boolean isSuperSet(Set<ParameterizedFlowPermission> superset, Set<ParameterizedFlowPermission> subset) {
            if (superset.containsAll(subset) || superset.contains(ANY)) {
                return true;
            }
            for (ParameterizedFlowPermission flow : subset) {
                if (!isMatchInSet(flow, superset)) {
                    return false;
                }
            }
            return true;
        }

        
        @Override
        protected void addPolyRelations(QualifierHierarchy qualHierarchy,
                Map<AnnotationMirror, Set<AnnotationMirror>> fullMap,
                Map<AnnotationMirror, AnnotationMirror> polyQualifiers, Set<AnnotationMirror> tops,
                Set<AnnotationMirror> bottoms) {
            AnnotationUtils.updateMappingToImmutableSet(fullMap, NOSOURCE,
                    Collections.singleton(POLYALL));
            AnnotationUtils.updateMappingToImmutableSet(fullMap, NOSOURCE,
                    Collections.singleton(POLYSOURCE));
            AnnotationUtils.updateMappingToImmutableSet(fullMap, ANYSINK,
                    Collections.singleton(POLYALL));
            AnnotationUtils.updateMappingToImmutableSet(fullMap, ANYSINK,
                    Collections.singleton(POLYSINK));
            Set<AnnotationMirror> polyallTops = AnnotationUtils.createAnnotationSet();
            polyallTops.add(ANYSOURCE);
            polyallTops.add(NOSINK);
            AnnotationUtils.updateMappingToImmutableSet(fullMap, POLYALL, polyallTops);
            AnnotationUtils.updateMappingToImmutableSet(fullMap, POLYSOURCE,
                    Collections.singleton(ANYSOURCE));
            AnnotationUtils.updateMappingToImmutableSet(fullMap, POLYSINK,
                    Collections.singleton(NOSINK));
        }

        @Override
        public AnnotationMirror leastUpperBound(AnnotationMirror a1, AnnotationMirror a2) {

            if (isSubtype(a1, a2))
                return a2;
            if (isSubtype(a2, a1))
                return a1;

            if (AnnotationUtils.areSameIgnoringValues(a1, a2)) {
                if (AnnotationUtils.areSameIgnoringValues(a1, SOURCE)) {
                    final Set<ParameterizedFlowPermission> superset = Flow.unionSources(a1, a2);
                    return boundSource(superset);

                } else if (AnnotationUtils.areSameIgnoringValues(a1, SINK)) {
                    final Set<ParameterizedFlowPermission> intersection = Flow.intersectSinks(a1, a2);
                    return boundSink(intersection);
                }
                // Poly Flows must be handled as if they are Top Type
            } else if (AnnotationUtils.areSame(a1, POLYSINK)) {
                if (AnnotationUtils.areSameIgnoringValues(a2, SINK)) {
                    return boundSink(new TreeSet<ParameterizedFlowPermission>());
                }

            } else if (AnnotationUtils.areSame(a2, POLYSINK)) {
                if (AnnotationUtils.areSameIgnoringValues(a1, SINK)) {
                    return boundSink(new TreeSet<ParameterizedFlowPermission>());
                }
            } else if (AnnotationUtils.areSame(a1, POLYSOURCE)) {
                if (AnnotationUtils.areSameIgnoringValues(a2, SOURCE)) {
                    Set<ParameterizedFlowPermission> top = new TreeSet<ParameterizedFlowPermission>();
                    top.add(ANY);
                    return boundSource(top);
                }

            } else if (AnnotationUtils.areSame(a2, POLYSOURCE)) {
                if (AnnotationUtils.areSameIgnoringValues(a1, SOURCE)) {
                    Set<ParameterizedFlowPermission> top = new TreeSet<ParameterizedFlowPermission>();
                    top.add(ANY);
                    return boundSource(top);
                }
            }

            return super.leastUpperBound(a1, a2);
        }

        @Override
        public AnnotationMirror greatestLowerBound(AnnotationMirror a1, AnnotationMirror a2) {

            if (AnnotationUtils.areSame(a1, a2))
                return a1;

            if (AnnotationUtils.areSameIgnoringValues(a1, a2)) {
                if (AnnotationUtils.areSameIgnoringValues(a1, SOURCE)) {
                    final Set<ParameterizedFlowPermission> intersection = Flow.intersectSources(a1, a2);
                    return boundSource(intersection);

                } else if (AnnotationUtils.areSameIgnoringValues(a1, SINK)) {
                    final Set<ParameterizedFlowPermission> superSet = Flow.unionSinks(a1, a2);
                    return boundSink(superSet);

                }
                // Poly Flows must be handled as if they are Bottom Type
            } else if (AnnotationUtils.areSame(a1, POLYSINK)) {
                if (AnnotationUtils.areSameIgnoringValues(a2, SINK)) {
                    Set<ParameterizedFlowPermission> bottom = new TreeSet<ParameterizedFlowPermission>();
                    bottom.add(ANY);
                    return boundSink(bottom);
                }

            } else if (AnnotationUtils.areSame(a2, POLYSINK)) {
                if (AnnotationUtils.areSameIgnoringValues(a1, SINK)) {
                    Set<ParameterizedFlowPermission> bottom = new TreeSet<ParameterizedFlowPermission>();
                    bottom.add(ANY);
                    return boundSink(bottom);
                }
            } else if (AnnotationUtils.areSame(a1, POLYSOURCE)) {
                if (AnnotationUtils.areSameIgnoringValues(a2, SOURCE)) {
                    return boundSource(new TreeSet<ParameterizedFlowPermission>());
                }

            } else if (AnnotationUtils.areSame(a2, POLYSOURCE)) {
                if (AnnotationUtils.areSameIgnoringValues(a1, SOURCE)) {
                    return boundSource(new TreeSet<ParameterizedFlowPermission>());
                }
            }
            return super.greatestLowerBound(a1, a2);
        }

        private AnnotationMirror boundSource(final Set<ParameterizedFlowPermission> flowSource) {

            final AnnotationMirror am;
            if (ParameterizedFlowPermission.coarsePermissionExists(ANY, flowSource)) { // contains all
                                                           // Source
                am = getTopAnnotation(SOURCE);
            } else if (flowSource.isEmpty()) {
                am = getBottomAnnotation(SOURCE);
            } else {
                am = createAnnoFromSource(flowSource);
            }
            return am;
        }

        private AnnotationMirror boundSink(final Set<ParameterizedFlowPermission> flowSink) {
            final AnnotationMirror am;
            if (flowSink.isEmpty()) {
                am = getTopAnnotation(SINK);
            } else if (ParameterizedFlowPermission.coarsePermissionExists(ANY, flowSink)) { // contains all
                                                                // Sink
                am = getBottomAnnotation(SINK);
            } else {
                am = createAnnoFromSink(flowSink);
            }
            return am;
        }
    }

    public FlowPolicy getFlowPolicy() {
        return flowPolicy;
    }

    public FlowAnalyzer getFlowAnalizer() {
        return flowAnalizer;
    }
    
    public static boolean isMatchInSet(ParameterizedFlowPermission flowToMatch, Set<ParameterizedFlowPermission> flows) {
        for (ParameterizedFlowPermission flow : flows) {
            if (flowToMatch.getPermission() == flow.getPermission()) {
                if (allParametersMatch(flowToMatch.getParameters(), flow.getParameters())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public static boolean allParametersMatch(List<String> childParams, List<String> parentParams) {
        for (String currChildParam : childParams) {
            if (!singleParametersMatch(currChildParam, parentParams)) {
                return false;
            }
        }
        return true;
    }
    
    public static boolean singleParametersMatch(String param, List<String> parameters) {
        for (String currParam : parameters) {
            if (wildcardMatch(param, currParam)) {
                return true;
            }
        }
        return false;
    }

    public static boolean wildcardMatch(String child, String parent) {
        String regex = parent.replaceAll("\\*", "(.*)");
        return child.matches(regex);
    }
}
