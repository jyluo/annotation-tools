package annotator.specification;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import org.objectweb.asm.ClassReader;

import annotations.Annotation;
import annotations.AnnotationFactory;
import annotations.Annotation;
import annotations.el.*;
import annotations.field.AnnotationFieldType;
import annotations.io.IndexFileParser;
import annotations.util.coll.VivifyingMap;
import annotator.find.Criteria;
import annotator.find.Insertion;
import annotator.scanner.MethodOffsetClassVisitor;

import com.sun.source.tree.Tree;

import plume.FileIOException;
import plume.Pair;

public class IndexFileSpecification implements Specification {

  private List<Insertion> insertions = new ArrayList<Insertion>();
  private AScene scene;
  private String indexFileName;

  // If set, do not attempt to read class files with Asm.
  // Mostly for debugging and workarounds.
  public static boolean noAsm = false;

  private static boolean debug = false;

  public IndexFileSpecification(String indexFileName) {
    this.indexFileName = indexFileName;
    scene = new AScene();
  }

  public List<Insertion> parse() throws FileIOException {
    try {
      IndexFileParser.parseFile(indexFileName, scene);
    } catch(FileIOException e) {
      throw e;
    } catch(Exception e) {
      throw new RuntimeException("Exception while parsing index file", e);
    }

    if (debug) {
      System.out.printf("Scene parsed from %s:%n", indexFileName);
      System.out.println(scene.unparse());
    }

    parseScene();
//    debug("---------------------------------------------------------");
    return this.insertions;
  }

  private static void debug(String s) {
    if (debug) {
      System.out.println(s);
    }
  }

  private static void debug(String s, Object... args) {
    if (debug) {
      System.out.printf(s, args);
    }
  }

  /** Fill in this.insertions with insertion pairs. */
  private void parseScene() {
    debug("parseScene()");

    // Empty criterion to work from.
    CriterionList clist = new CriterionList();

    VivifyingMap<String, AElement> packages = scene.packages;
    for (Map.Entry<String, AElement> entry : packages.entrySet()) {
      parsePackage(clist, entry.getKey(), entry.getValue());
    }

    VivifyingMap<String, AClass> classes = scene.classes;
    for (Map.Entry<String, AClass> entry : classes.entrySet()) {
      parseClass(clist, entry.getKey(), entry.getValue());
    }
  }

  // There is no .class file corresponding to the package-info.java file.
  private void parsePackage(CriterionList clist, String packageName, AElement element) {
    // There is no Tree.Kind.PACKAGE, only Tree.Kind.COMPILATION_UNIT.
    // CompilationUnitTree has getPackageName and getPackageAnnotations
    CriterionList packageClist = clist.add(Criteria.packageDecl(packageName));
    parseElement(packageClist, element);
  }



  /** Fill in this.insertions with insertion pairs.
   * @param className is fully qualified
   */
  private void parseClass(CriterionList clist, String className, AClass clazz) {

    if (! noAsm) {
      //  load extra info using asm
      debug("parseClass(" + className + ")");
      try {
        ClassReader classReader = new ClassReader(className);
        MethodOffsetClassVisitor cv = new MethodOffsetClassVisitor();
        classReader.accept(cv, false);
        debug("Done reading " + className + ".class");
      } catch(IOException e) {
        // If .class file not found, still proceed, in case
        // user only wants method signature annotations.
        System.out.println("Warning: IndexFileSpecification did not find classfile for: " + className);
        // throw new RuntimeException("IndexFileSpecification.parseClass: " + e);
      } catch (RuntimeException e) {
        System.err.println("IndexFileSpecification had a problem reading class: " + className);
        throw e;
      } catch (Error e) {
        System.err.println("IndexFileSpecification had a problem reading class: " + className);
        throw e;
      }
    }

    CriterionList clistSansClass = clist;

    clist = clist.add(Criteria.inClass(className, true));
    CriterionList classClist = clistSansClass.add(Criteria.is(Tree.Kind.CLASS, className));
    parseElement(classClist, clazz);

    VivifyingMap<BoundLocation, ATypeElement> bounds = clazz.bounds;
    for (Entry<BoundLocation, ATypeElement> entry : bounds.entrySet()) {
      BoundLocation boundLoc = entry.getKey();
      ATypeElement bound = entry.getValue();
      CriterionList boundList = clist.add(Criteria.classBound(className, boundLoc));
      for (Entry<InnerTypeLocation, AElement> innerEntry : bound.innerTypes.entrySet()) {
        InnerTypeLocation innerLoc = innerEntry.getKey();
        AElement ae = innerEntry.getValue();
        CriterionList innerBoundList = boundList.add(Criteria.atLocation(innerLoc));
        parseElement(innerBoundList, ae);
      }
      CriterionList outerClist = boundList.add(Criteria.atLocation());
      parseElement(outerClist, bound);
    }

    clist = clist.add(Criteria.inClass(className, /*exactMatch=*/ false));

    for (Map.Entry<String, ATypeElement> entry : clazz.fields.entrySet()) {
//      clist = clist.add(Criteria.notInMethod()); // TODO: necessary? what is in class but not in method?
      parseField(clist, entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, AMethod> entry : clazz.methods.entrySet()) {
      parseMethod(clist, entry.getKey(), entry.getValue());
    }

    debug("parseClass(" + className + "):  done");
  }

  /** Fill in this.insertions with insertion pairs. */
  private void parseField(CriterionList clist, String fieldName, ATypeElement field) {
    clist = clist.add(Criteria.field(fieldName));

    // parse declaration annotations
    parseElement(clist, field);

    parseInnerAndOuterElements(clist, field.type);
  }

  /** Fill in this.insertions with insertion pairs. */
  private void parseElement(CriterionList clist, AElement element) {
    for (Pair<String,Boolean> p : getElementAnnotation(element)) {
      String annotationString = p.a;
      Boolean isDeclarationAnnotation = p.b;
      Insertion ins = new Insertion(annotationString, clist.criteria(),
                                    isDeclarationAnnotation);
      debug("parsed: " + ins);
      this.insertions.add(ins);
    }
  }

  /** Fill in this.insertions with insertion pairs. */
  private void parseInnerAndOuterElements(CriterionList clist, ATypeElement typeElement) {
    for (Entry<InnerTypeLocation, AElement> innerEntry: typeElement.innerTypes.entrySet()) {
      InnerTypeLocation innerLoc = innerEntry.getKey();
      AElement innerElement = innerEntry.getValue();
      CriterionList innerClist = clist.add(Criteria.atLocation(innerLoc));
      parseElement(innerClist, innerElement);
    }
    CriterionList outerClist = clist.add(Criteria.atLocation());
    parseElement(outerClist, typeElement);
  }

  // Returns a string representation of the annotations at the element.
  private Set<Pair<String,Boolean>> getElementAnnotation(AElement element) {
    Set<Pair<String,Boolean>> result = new LinkedHashSet<Pair<String,Boolean>>(element.tlAnnotationsHere.size());
    for (Annotation a : element.tlAnnotationsHere) {
      AnnotationDef adef = a.def;
      String annotationString = "@" + adef.name;

      if (a.fieldValues.size() == 1 && a.fieldValues.containsKey("value")) {
        annotationString += "(" + formatFieldValue(a, "value") + ")";
      } else if (a.fieldValues.size() > 0) {
        annotationString += "(";
        boolean first = true;
        for (String field : a.fieldValues.keySet()) {
          // parameters of the annotation
          if (!first) {
            annotationString += ", ";
          }
          annotationString += field + "=" + formatFieldValue(a, field);
          first = false;
        }
        annotationString += ")";
      }
      // annotationString += " ";
      result.add(new Pair<String,Boolean>(annotationString,
                                          ! adef.isTypeAnnotation()));
    }
    return result;
  }

  private String formatFieldValue(Annotation a, String field) {
    AnnotationFieldType fieldType = a.def.fieldTypes.get("value");
    assert fieldType != null;
    return fieldType.format(a.fieldValues.get("value"));
  }

  private void parseMethod(CriterionList clist, String methodName, AMethod method) {
    // Being "in" a method refers to being somewhere in the
    // method's tree, which includes return types, parameters, receiver, and
    // elements inside the method body.
    clist = clist.add(Criteria.inMethod(methodName));

    // parse declaration annotations
    parseElement(clist, method);

    // parse receiver
    CriterionList receiverClist = clist.add(Criteria.receiver(methodName));
    parseElement(receiverClist, method.receiver);

    // parse return type
    CriterionList returnClist = clist.add(Criteria.returnType(methodName));
    parseInnerAndOuterElements(returnClist, method.returnType);

    // parse bounds of method
    for (Entry<BoundLocation, ATypeElement> entry : method.bounds.entrySet()) {
      BoundLocation boundLoc = entry.getKey();
      ATypeElement bound = entry.getValue();
      CriterionList boundClist = clist.add(Criteria.methodBound(methodName, boundLoc));
      parseInnerAndOuterElements(boundClist, bound);
    }

    // parse parameters of method
    for (Entry<Integer, ATypeElement> entry : method.parameters.entrySet()) {
      Integer index = entry.getKey();
      ATypeElement param = entry.getValue();
      CriterionList paramClist = clist.add(Criteria.param(methodName, index));
      // parse declaration annotations
      parseElement(clist, param);
      parseInnerAndOuterElements(paramClist, param.type);
    }

    // parse locals of method
    for (Entry<LocalLocation, ATypeElement> entry : method.locals.entrySet()) {
      LocalLocation loc = entry.getKey();
      ATypeElement var = entry.getValue();
      CriterionList varClist = clist.add(Criteria.local(methodName, loc));
      parseInnerAndOuterElements(varClist, var);
    }

    // parse typecasts of method
    for (Entry<Integer, ATypeElement> entry : method.typecasts.entrySet()) {
      Integer offset = entry.getKey();
      ATypeElement cast = entry.getValue();
      CriterionList castClist = clist.add(Criteria.cast(methodName, offset));
      parseInnerAndOuterElements(castClist, cast);
    }

    // parse news (object creation) of method
    for (Entry<Integer, ATypeElement> entry : method.news.entrySet()) {
      Integer offset = entry.getKey();
      ATypeElement newObject = entry.getValue();
      CriterionList newClist = clist.add(Criteria.newObject(methodName, offset));
      parseInnerAndOuterElements(newClist, newObject);
    }

    // parse instanceofs of method
    for (Entry<Integer, ATypeElement> entry : method.instanceofs.entrySet()) {
      Integer offset = entry.getKey();
      ATypeElement instanceOf = entry.getValue();
      CriterionList instanceOfClist = clist.add(Criteria.instanceOf(methodName, offset));
      parseInnerAndOuterElements(instanceOfClist, instanceOf);
    }
  }
}
