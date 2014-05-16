package jp.portown.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

import javax.swing.JOptionPane;

import com.change_vision.jude.api.inf.AstahAPI;
import com.change_vision.jude.api.inf.exception.ProjectNotFoundException;
import com.change_vision.jude.api.inf.model.IAssociation;
import com.change_vision.jude.api.inf.model.IAttribute;
import com.change_vision.jude.api.inf.model.IClass;
import com.change_vision.jude.api.inf.model.IDependency;
import com.change_vision.jude.api.inf.model.IElement;
import com.change_vision.jude.api.inf.model.IGeneralization;
import com.change_vision.jude.api.inf.model.IModel;
import com.change_vision.jude.api.inf.model.INamedElement;
import com.change_vision.jude.api.inf.model.IPackage;
import com.change_vision.jude.api.inf.model.IRealization;
import com.change_vision.jude.api.inf.model.IUsage;
import com.change_vision.jude.api.inf.project.ProjectAccessor;
import com.change_vision.jude.api.inf.ui.IPluginActionDelegate;
import com.change_vision.jude.api.inf.ui.IWindow;

public class TemplateAction implements IPluginActionDelegate {

  public Object run(IWindow window) throws UnExpectedException {
    try {
      AstahAPI api = AstahAPI.getAstahAPI();
      ProjectAccessor projectAccessor = api.getProjectAccessor();
      final IModel project = projectAccessor.getProject();
      final HashMap<String, String> map = new HashMap<>();
      final MetricsVisitor visitor = new MetricsVisitor();
      visitor.visit(project);
      //System.out.println(visitor.getString());
      final StringBuilder builder = new StringBuilder();
      final ArrayList<PackageModel> packages = new ArrayList<>(visitor.mPackages.values());
      Collections.sort(packages, new Comparator<PackageModel>() {
        @Override public int compare(PackageModel lhs, PackageModel rhs) {
          return lhs.getFullName().compareTo(rhs.getFullName());
        }
      });
      for (PackageModel pm : packages) {
        //System.out.println(pm.toString());
        builder.append(pm.getFullName())
          .append(" Classes: ").append(pm.getNumOfClasses())
          .append(" Client: ").append(pm.numOfClientDependencies())
          .append(" Deps: ").append(pm.getNumOfDependencies())
          .append(" A: ").append(pm.abstraction())
          .append(" I: ").append(pm.instability())
          .append(" D: ").append(pm.distance())
          .append("\n");
      }
      System.out.println(builder.toString());
    } catch (ProjectNotFoundException e) {
      String message = "Project is not opened.Please open the project or create new project.";
      JOptionPane.showMessageDialog(window.getParent(), message, "Warning", JOptionPane.WARNING_MESSAGE);
    } catch (Exception e) {
      JOptionPane.showMessageDialog(window.getParent(), "Unexpected error has occurred.", "Alert", JOptionPane.ERROR_MESSAGE);
      throw new UnExpectedException();
    }
    return null;
  }

  private interface ElementVisitor {
    void visit(IPackage pack);
    void visit(IClass clazz);
  }

  private static class ElementVisitorAdapter {

    private static void applyVisitor(INamedElement element, ElementVisitor visitor) {
      if (element instanceof IPackage) {
        visitor.visit((IPackage)element);
      } else if (element instanceof IClass) {
        visitor.visit((IClass)element);
      }
    }
  }

  private static class MetricsVisitor implements ElementVisitor {

    private final StringBuilder mBuilder = new StringBuilder();

    private IPackage mPackage = null;
    private final HashMap<String, PackageModel> mPackages = new HashMap<>();

    @Override
    public void visit(IPackage pack) {
      final IPackage p = mPackage;
      mPackage = pack;
      for (INamedElement elem : pack.getOwnedElements()) {
        ElementVisitorAdapter.applyVisitor(elem, this);
      }
      mPackage = p;
    }

    @Override
    public void visit(IClass clazz) {
      final ClassModel classModel = new ClassModel(clazz);

      final PackageModel packageModel = getPackage(classModel.getPackage());
      packageModel.addClass(classModel);

      mBuilder.append("  ");
      if (classModel.isAbstract()) mBuilder.append("abstract"); else mBuilder.append("concrete");
      mBuilder.append(" class: ").append(classModel.getFullName()).append("\n");

      for (IDependency dep : clazz.getClientDependencies()) {
        mBuilder.append("    ..> ").append(dep.getSupplier().getName()).append("\n");
        packageModel.addAssociation(Association.fromTo((IClass)dep.getClient(), (IClass)dep.getSupplier()));
      }

      for (IRealization dep : clazz.getClientRealizations()) {
        mBuilder.append("    ..|> ").append(dep.getSupplier().getName()).append("\n");
        packageModel.addAssociation(Association.fromTo((IClass)dep.getClient(), (IClass)dep.getSupplier()));
      }

      for (IUsage dep : clazz.getClientUsages()) {
        mBuilder.append("    .U.> ").append(dep.getSupplier().getName()).append("\n");
        packageModel.addAssociation(Association.fromTo((IClass)dep.getClient(), (IClass)dep.getSupplier()));
      }

      for (IGeneralization dep : clazz.getSpecializations()) {
        mBuilder.append("    <|- ").append(dep.getSubType().getName()).append("\n");
        packageModel.addAssociation(Association.fromTo(dep.getSubType(), dep.getSuperType()));
      }

      for (IDependency dep : clazz.getSupplierDependencies()) {
        mBuilder.append("    <.. ").append(dep.getClient().getName()).append("\n");
        packageModel.addAssociation(Association.fromTo((IClass)dep.getClient(), (IClass)dep.getSupplier()));
      }

      for (IRealization dep : clazz.getSupplierRealizations()) {
        mBuilder.append("    <|.. ").append(dep.getClient().getName()).append("\n");
        packageModel.addAssociation(Association.fromTo((IClass)dep.getClient(), (IClass)dep.getSupplier()));
      }

      for (IUsage dep : clazz.getSupplierUsages()) {
        mBuilder.append("    <.U. ").append(dep.getClient().getName()).append("\n");
        packageModel.addAssociation(Association.fromTo((IClass)dep.getClient(), (IClass)dep.getSupplier()));
      }

      for (IGeneralization dep : clazz.getGeneralizations()) {
        mBuilder.append("    -|> ").append(dep.getSuperType().getName()).append("\n");
        packageModel.addAssociation(Association.fromTo(dep.getSubType(), dep.getSuperType()));
      }

      for (IAttribute attr : clazz.getAttributes()) {
        final IAssociation assoc = attr.getAssociation();
        if (assoc == null) continue;

        IAttribute selfEnd = null;
        final ArrayList<IAttribute> otherEnds = new ArrayList<>();
        for (IAttribute end : assoc.getMemberEnds()) {
          if (end.getType().equals(clazz)) {
            selfEnd = end;
            continue;
          }
          otherEnds.add(end);
        }
        assert selfEnd != null;
        assert otherEnds.size() < 2;
        if (otherEnds.isEmpty()) continue;

        mBuilder.append("    ").append(selfEnd.getType().getName());
        if (selfEnd.getNavigability().equals("Navigable")) mBuilder.append("<");
        if (selfEnd.isAggregate()) mBuilder.append("<>");
        if (selfEnd.isComposite()) mBuilder.append("*");
        if (selfEnd.isDerived()) mBuilder.append("<|");
        mBuilder.append("-");
        for (IAttribute end : otherEnds) {
          if (end.getNavigability().equals("Navigable")) mBuilder.append(">");
          if (end.isAggregate()) mBuilder.append("<>");
          if (end.isComposite()) mBuilder.append("*");
          if (end.isDerived()) mBuilder.append("|>");
          mBuilder.append(end.getType().getName()).append("\n");
        }

        final boolean selfNavigable = selfEnd.getNavigability().equals("Navigable");
        final boolean fromSelf = selfEnd.isAggregate() || selfEnd.isComposite() || selfEnd.isDerived();
        for (IAttribute end : otherEnds) {
          final boolean otherNavigable = end.getNavigability().equals("Navigable");
          final boolean fromOther = end.isAggregate() || end.isComposite() || end.isDerived();

          if (selfNavigable || fromOther) packageModel.addAssociation(Association.fromTo(end.getType(), selfEnd.getType()));
          if (otherNavigable || fromSelf) packageModel.addAssociation(Association.fromTo(selfEnd.getType(), end.getType()));
        }
      }
    }

    public String getString() {
      return mBuilder.toString();
    }

    private PackageModel getPackage(IPackage p) {
      final String fullName = p.getFullName(".");
      final PackageModel packageModel = mPackages.get(fullName);
      if (packageModel != null) return packageModel;

      final PackageModel pm = new PackageModel(p);
      mPackages.put(fullName, pm);
      return pm;
    }
  }


  private static class PackageModel {

    private final IPackage mPackage;
    private final HashSet<ClassModel> mClasses = new HashSet<>();
    private final HashSet<Association> mAssociations = new HashSet<>();

    public PackageModel(IPackage pack) {
      mPackage = pack;
    }

    public String getFullName() {
      return mPackage.getFullName(".");
    }

    public void addClass(ClassModel classModel) {
      mClasses.add(classModel);
    }

    public void addAssociation(Association assoc) {
      mAssociations.add(assoc);
    }

    public float abstraction() {
      if (hasNoClasses()) return Float.NaN;
      return (float)numOfAbstractClasses() / (float)getNumOfClasses();
    }

    public int numOfAbstractClasses() {
      int num = 0;
      for (ClassModel c : mClasses) {
        if (c.isAbstract()) ++num;
      }
      return num;
    }

    public int getNumOfClasses() {
      return mClasses.size();
    }

    public boolean hasNoClasses() {
      return mClasses.isEmpty();
    }

    public float instability() {
      return (float)numOfClientDependencies() / (float)getNumOfDependencies();
    }

    public int numOfClientDependencies() {
      int num = 0;
      for (Association assoc : mAssociations) {
        if (!hasClass(assoc.getFrom()) || hasClass(assoc.getTo())) continue;
        ++num;
      }
      return num;
    }

    public int getNumOfDependencies() {
      int num = 0;
      for (Association assoc : mAssociations) {
        if (hasClass(assoc.getFrom()) == hasClass(assoc.getTo())) continue;
        ++num;
      }
      return num;
    }

    public float distance() {
      return Math.abs(abstraction() + instability() - 1.0f);
    }

    private boolean hasClass(ClassModel classModel) {
      return mClasses.contains(classModel);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) return true;
      if (!(obj instanceof PackageModel)) return false;

      final PackageModel other = (PackageModel)obj;

      return other.mPackage.equals(mPackage);
    }

    @Override
    public int hashCode() {
      return mPackage.hashCode();
    }

    @Override
    public String toString() {
      return "<package name=" + getFullName() + ", classes=" + mClasses + ", associations=" + mAssociations + ">";
    }
  }

  private static class ClassModel {

    private final IClass mClass;

    public ClassModel(IClass clazz) {
      mClass = clazz;
    }

    public String getName() {
      return mClass.getName();
    }

    public String getFullName() {
      return mClass.getFullName(".");
    }

    public boolean isAbstract() {
      return mClass.isAbstract() || mClass.hasStereotype("interface");
    }

    public IPackage getPackage() {
      return (IPackage)mClass.getOwner();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) return true;
      if (!(obj instanceof ClassModel)) return false;

      final ClassModel other = (ClassModel)obj;

      return other.mClass.equals(mClass);
    }

    @Override
    public int hashCode() {
      return mClass.hashCode();
    }

    @Override
    public String toString() {
      return "<class " + getName() + ">";
    }
  }

  private static class Association {

    private final ClassModel mFrom;
    private final ClassModel mTo;

    public static Association fromTo(IClass from, IClass to) {
      return new Association(new ClassModel(from), new ClassModel(to));
    }

    private Association(ClassModel from, ClassModel to) {
      mFrom = from;
      mTo = to;
    }

    public ClassModel getFrom() {
      return mFrom;
    }

    public ClassModel getTo() {
      return mTo;
    }

    @Override
    public String toString() {
      return "<assoc from=" + mFrom + ", to=" + mTo + ">";
    }
  }
}
