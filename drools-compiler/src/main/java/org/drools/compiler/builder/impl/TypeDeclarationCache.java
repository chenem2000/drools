package org.drools.compiler.builder.impl;

import org.drools.compiler.compiler.PackageRegistry;
import org.drools.compiler.compiler.TypeDeclarationError;
import org.drools.compiler.lang.descr.PackageDescr;
import org.drools.core.factmodel.ClassDefinition;
import org.drools.core.factmodel.FieldDefinition;
import org.drools.core.factmodel.traits.Thing;
import org.drools.core.factmodel.traits.Trait;
import org.drools.core.rule.TypeDeclaration;
import org.drools.core.util.ClassUtils;
import org.kie.api.definition.type.ClassReactive;
import org.kie.api.definition.type.Modifies;
import org.kie.api.definition.type.Position;
import org.kie.api.definition.type.PropertyReactive;
import org.kie.api.definition.type.Role;
import org.kie.api.io.Resource;
import org.kie.api.runtime.rule.Match;
import org.kie.internal.builder.conf.PropertySpecificOption;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.drools.core.util.BitMaskUtil.isSet;

public class TypeDeclarationCache {

    private KnowledgeBuilderImpl kbuilder;
    private Map<String, TypeDeclaration> cacheTypes = new HashMap<String, TypeDeclaration>();
    private final Map<String, TypeDeclaration> builtinTypes = new HashMap<String, TypeDeclaration>();

    TypeDeclarationCache( KnowledgeBuilderImpl kbuilder ) {
        this.kbuilder = kbuilder;
        initBuiltinTypeDeclarations();
    }

    private void initBuiltinTypeDeclarations() {
        TypeDeclaration colType = new TypeDeclaration( Collection.class.getSimpleName() );
        colType.setTypesafe( false );
        colType.setTypeClass( Collection.class );
        builtinTypes.put( Collection.class.getCanonicalName(),
                          colType );

        TypeDeclaration mapType = new TypeDeclaration( Map.class.getSimpleName() );
        mapType.setTypesafe( false );
        mapType.setTypeClass( Map.class );
        builtinTypes.put( Map.class.getCanonicalName(),
                          mapType );

        TypeDeclaration activationType = new TypeDeclaration( Match.class.getSimpleName() );
        activationType.setTypesafe( false );
        activationType.setTypeClass( Match.class );
        builtinTypes.put( Match.class.getCanonicalName(),
                          activationType );

        TypeDeclaration thingType = new TypeDeclaration( Thing.class.getSimpleName() );
        thingType.setKind( TypeDeclaration.Kind.TRAIT );
        thingType.setTypeClass( Thing.class );
        builtinTypes.put( Thing.class.getCanonicalName(),
                          thingType );
    }


    public TypeDeclaration getAndRegisterTypeDeclaration( Class<?> cls, String packageName ) {
        if (cls.isPrimitive() || cls.isArray()) {
            return null;
        }
        TypeDeclaration typeDeclaration = getCachedTypeDeclaration(cls);
        if (typeDeclaration != null) {
            registerTypeDeclaration(packageName, typeDeclaration);
            return typeDeclaration;
        }
        typeDeclaration = getExistingTypeDeclaration(cls);
        if (typeDeclaration != null) {
            initTypeDeclaration(cls, typeDeclaration);
            return typeDeclaration;
        }

        typeDeclaration = createTypeDeclarationForBean(cls);
        initTypeDeclaration(cls, typeDeclaration);
        registerTypeDeclaration(packageName, typeDeclaration);
        return typeDeclaration;
    }

    TypeDeclaration getTypeDeclaration( Class<?> cls ) {
        if (cls.isPrimitive() || cls.isArray())
            return null;

        // If this class has already been accessed, it'll be in the cache
        TypeDeclaration tdecl = getCachedTypeDeclaration(cls);
        return tdecl != null ? tdecl : createTypeDeclaration(cls);
    }


    private void registerTypeDeclaration( String packageName,
                                          TypeDeclaration typeDeclaration ) {
        if (typeDeclaration.getNature() == TypeDeclaration.Nature.DECLARATION || packageName.equals(typeDeclaration.getTypeClass().getPackage().getName())) {
            PackageRegistry packageRegistry = kbuilder.getPackageRegistry(packageName);
            if (packageRegistry != null) {
                packageRegistry.getPackage().addTypeDeclaration(typeDeclaration);
            } else {
                kbuilder.newPackage(new PackageDescr(packageName, ""));
                kbuilder.getPackageRegistry(packageName).getPackage().addTypeDeclaration(typeDeclaration);
            }
        }
    }


    private TypeDeclaration createTypeDeclaration(Class<?> cls) {
        TypeDeclaration typeDeclaration = getExistingTypeDeclaration(cls);

        if (typeDeclaration == null) {
            typeDeclaration = createTypeDeclarationForBean(cls);
        }

        initTypeDeclaration(cls, typeDeclaration);
        return typeDeclaration;
    }

    private TypeDeclaration getCachedTypeDeclaration(Class<?> cls) {
        if (this.cacheTypes == null) {
            this.cacheTypes = new HashMap<String, TypeDeclaration>();
            return null;
        } else {
            return cacheTypes.get(cls.getName());
        }
    }

    private TypeDeclaration getExistingTypeDeclaration(Class<?> cls) {
        // Check if we are in the built-ins
        TypeDeclaration typeDeclaration = this.builtinTypes.get((cls.getName()));
        if (typeDeclaration == null) {
            // No built-in
            // Check if there is a user specified typedeclr
            PackageRegistry pkgReg = kbuilder.getPackageRegistry( ClassUtils.getPackage( cls ));
            if (pkgReg != null) {
                String className = cls.getName();
                String typeName = className.substring(className.lastIndexOf(".") + 1);
                typeDeclaration = pkgReg.getPackage().getTypeDeclaration(typeName);
            }
        }
        return typeDeclaration;
    }

    private void initTypeDeclaration(Class<?> cls,
                                     TypeDeclaration typeDeclaration) {
        ClassDefinition clsDef = typeDeclaration.getTypeClassDef();
        if (clsDef == null) {
            clsDef = new ClassDefinition();
            ClassDefinitionFactory.populateDefinitionFromClass( clsDef, cls, cls.getAnnotation( Trait.class ) != null );
            typeDeclaration.setTypeClassDef(clsDef);
        } else {
            processFieldsPosition( cls, clsDef, typeDeclaration );
        }

        if (typeDeclaration.isPropertyReactive()) {
            processModifiedProps(cls, clsDef);
        }


        // build up a set of all the super classes and interfaces
        Set<TypeDeclaration> tdecls = new LinkedHashSet<TypeDeclaration>();

        tdecls.add(typeDeclaration);
        buildTypeDeclarations(cls,
                              tdecls);

        // Iterate and for each typedeclr assign it's value if it's not already set
        // We start from the rear as those are the furthest away classes and interfaces
        TypeDeclaration[] tarray = tdecls.toArray(new TypeDeclaration[tdecls.size()]);
        for (int i = tarray.length - 1; i >= 0; i--) {
            TypeDeclaration currentTDecl = tarray[i];
            if (!isSet(typeDeclaration.getSetMask(),
                       TypeDeclaration.ROLE_BIT) && isSet(currentTDecl.getSetMask(),
                                                          TypeDeclaration.ROLE_BIT)) {
                typeDeclaration.setRole(currentTDecl.getRole());
            }
            if (!isSet(typeDeclaration.getSetMask(),
                       TypeDeclaration.FORMAT_BIT) && isSet(currentTDecl.getSetMask(),
                                                            TypeDeclaration.FORMAT_BIT)) {
                typeDeclaration.setFormat(currentTDecl.getFormat());
            }
            if (!isSet(typeDeclaration.getSetMask(),
                       TypeDeclaration.TYPESAFE_BIT) && isSet(currentTDecl.getSetMask(),
                                                              TypeDeclaration.TYPESAFE_BIT)) {
                typeDeclaration.setTypesafe(currentTDecl.isTypesafe());
            }
        }

        this.cacheTypes.put(cls.getName(),
                            typeDeclaration);
    }

    private void processFieldsPosition( Class<?> cls,
                                        ClassDefinition clsDef,
                                        TypeDeclaration typeDeclaration ) {
        // it's a new type declaration, so generate the @Position for it
        Collection<Field> fields = new LinkedList<Field>();
        Class<?> tempKlass = cls;
        while (tempKlass != null && tempKlass != Object.class) {
            Collections.addAll( fields, tempKlass.getDeclaredFields() );
            tempKlass = tempKlass.getSuperclass();
        }

        FieldDefinition[] orderedFields = new FieldDefinition[ fields.size() ];

        for (Field fld : fields) {
            Position pos = fld.getAnnotation(Position.class);
            if (pos != null) {
                if (pos.value() < 0 || pos.value() >= fields.size()) {
                    kbuilder.addBuilderResult(new TypeDeclarationError(typeDeclaration,
                                                                       "Out of range position " + pos.value() + " for field '" + fld.getName() + "' on class " + cls.getName()));
                    continue;
                }
                if (orderedFields[pos.value()] != null) {
                    kbuilder.addBuilderResult(new TypeDeclarationError(typeDeclaration,
                                                                       "Duplicated position " + pos.value() + " for field '" + fld.getName() + "' on class " + cls.getName()));
                    continue;
                }
                FieldDefinition fldDef = clsDef.getField(fld.getName());
                if (fldDef == null) {
                    fldDef = new FieldDefinition(fld.getName(), fld.getType().getName());
                }
                fldDef.setIndex(pos.value());
                orderedFields[ pos.value() ] = fldDef;
            }
        }
        for (FieldDefinition fld : orderedFields) {
            if (fld != null) {
                // it's null if there is no @Position
                clsDef.addField(fld);
            }
        }
    }

    private void processModifiedProps(Class<?> cls,
                                      ClassDefinition clsDef) {
        for (Method method : cls.getDeclaredMethods()) {
            Modifies modifies = method.getAnnotation(Modifies.class);
            if (modifies != null) {
                String[] props = modifies.value();
                List<String> properties = new ArrayList<String>(props.length);
                for (String prop : props) {
                    properties.add(prop.trim());
                }
                clsDef.addModifiedPropsByMethod(method,
                                                properties);
            }
        }
    }

    private TypeDeclaration createTypeDeclarationForBean(Class<?> cls) {
        TypeDeclaration typeDeclaration = new TypeDeclaration(cls);

        PropertySpecificOption propertySpecificOption = kbuilder.getBuilderConfiguration().getPropertySpecificOption();
        boolean propertyReactive = propertySpecificOption.isPropSpecific(cls.isAnnotationPresent(PropertyReactive.class),
                                                                         cls.isAnnotationPresent(ClassReactive.class));

        kbuilder.setPropertyReactive( null, typeDeclaration, propertyReactive );

        Role role = cls.getAnnotation(Role.class);
        if (role != null) {
            typeDeclaration.setRole(role.value());
        }

        return typeDeclaration;
    }

    private void buildTypeDeclarations(Class<?> cls,
                                       Set<TypeDeclaration> tdecls) {
        // Process current interfaces
        Class<?>[] intfs = cls.getInterfaces();
        for (Class<?> intf : intfs) {
            buildTypeDeclarationInterfaces(intf,
                                           tdecls);
        }

        // Process super classes and their interfaces
        cls = cls.getSuperclass();
        while (cls != null && cls != Object.class) {
            if (!buildTypeDeclarationInterfaces(cls,
                                                tdecls)) {
                break;
            }
            cls = cls.getSuperclass();
        }

    }

    private boolean buildTypeDeclarationInterfaces(Class cls,
                                                   Set<TypeDeclaration> tdecls) {
        PackageRegistry pkgReg;

        TypeDeclaration tdecl = this.builtinTypes.get((cls.getName()));
        if (tdecl == null) {
            pkgReg = kbuilder.getPackageRegistry(ClassUtils.getPackage(cls));
            if (pkgReg != null) {
                tdecl = pkgReg.getPackage().getTypeDeclaration(cls.getSimpleName());
            }
        }
        if (tdecl != null) {
            if (!tdecls.add(tdecl)) {
                return false; // the interface already exists, return to stop recursion
            }
        }

        Class<?>[] intfs = cls.getInterfaces();
        for (Class<?> intf : intfs) {
            pkgReg = kbuilder.getPackageRegistry(ClassUtils.getPackage(intf));
            if (pkgReg != null) {
                tdecl = pkgReg.getPackage().getTypeDeclaration(intf.getSimpleName());
            }
            if (tdecl != null) {
                tdecls.add(tdecl);
            }
        }

        for (Class<?> intf : intfs) {
            if (!buildTypeDeclarationInterfaces(intf,
                                                tdecls)) {
                return false;
            }
        }

        return true;

    }


    void removeTypesGeneratedFromResource(Resource resource) {
        if (cacheTypes != null) {
            List<String> typesToBeRemoved = new ArrayList<String>();
            for (Map.Entry<String, TypeDeclaration> type : cacheTypes.entrySet()) {
                if (resource.equals(type.getValue().getResource())) {
                    typesToBeRemoved.add(type.getKey());
                }
            }
            for (String type : typesToBeRemoved) {
                cacheTypes.remove(type);
            }
        }
    }
}
