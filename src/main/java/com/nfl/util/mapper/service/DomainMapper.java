package com.nfl.util.mapper.service;

import com.nfl.util.mapper.CustomMappingObject;
import com.nfl.util.mapper.MappingFunction;
import com.nfl.util.mapper.MappingType;
import com.nfl.util.mapper.annotation.Mapping;
import com.nfl.util.mapper.annotation.MappingTo;
import com.nfl.util.mapper.annotation.PostProcessor;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * DomainMapper uses Map of String, Function to map one pojo to another
 */
@SuppressWarnings("unused")
@Component
public class DomainMapper {

    private static final Log log = LogFactory.getLog(DomainMapper.class);


    private TypeSafeCopy typeSafeCopy = new TypeSafeCopy();

    private String defaultMappingName;

    @Autowired
    private MappingService mappingService;

    public DomainMapper() throws NoSuchMethodException {
        defaultMappingName = (String) Mapping.class.getDeclaredMethod("name", new Class[]{}).getDefaultValue();
    }


    public <From, To> List<To> mapList(Class<To> toClass, Collection<From> list) {
        return this.mapList(toClass, list, MappingType.FULL, defaultMappingName);
    }


    public <From, To> List<To> mapList(Class<To> toClass, Collection<From> list, MappingType mappingType) {

        return this.mapList(toClass, list, mappingType, defaultMappingName);
    }

    public <From, To> List<To> mapList(Class<To> toClass, Collection<From> list, String mappingName) {
        return this.mapList(toClass, list, MappingType.FULL, mappingName);
    }

    public <From, To> List<To> mapList(Class<To> toClass, Collection<From> list, MappingType mappingType, String mappingName) {
        return list.parallelStream().map(o -> doMapping(toClass, mappingType, mappingName, o)).collect(Collectors.toList());
    }


    public <From, To> To map(Class<To> toClass, From from) {
        return this.map(toClass, from, defaultMappingName, MappingType.FULL);
    }

    public <From, To> To map(Class<To> toClass, From from, MappingType mappingType) {
        return this.map(toClass, from, defaultMappingName, mappingType);
    }

    public <From, To> To map(Class<To> toClass, From from, String mappingName) {
        return this.map(toClass, from, mappingName, MappingType.FULL);
    }

    public <From, To> To map(Class<To> toClass, From from, String mappingName, MappingType mappingType) {
        return this.doMapping(toClass, mappingType, mappingName, from);
    }


    @SuppressWarnings("unchecked")
    private <From, To> To doMapping(final Class<To> toClass, MappingType mappingType, String mappingName, From from) {//Object... from) {

        try {

            if (from == null) return null;

            final To to = toClass.newInstance();

            MappingType overrideMappingType = null;
            String customMappingName = mappingName;

            if (from instanceof CustomMappingObject) {
                CustomMappingObject cmo = (CustomMappingObject) from;
                overrideMappingType = cmo.getMappingType();
                customMappingName = cmo.getMappingName();
            }


            MappingFunction mappingFunction = mappingService.getMappingFunction(toClass, mappingType, from.getClass(), mappingName);

            Map<String, Function> mapping = mappingFunction.getMapping();
            if (mappingFunction.getMappingType() == MappingType.FULL_AUTO) {
                typeSafeCopy.copyProperties(to, from);
            }

            final Object finalFrom = from;


            assert mapping != null;

            boolean parallel = mappingFunction.isParallel();

            (parallel ? mapping.entrySet().parallelStream() : mapping.entrySet().stream()).forEach(entry ->
                    {
                        String toPropertyName = entry.getKey();
                        Function fromFunction = entry.getValue();
                        try {

                            Class toPropertyType = PropertyUtils.getPropertyType(to, toPropertyName);

                            //If the field is collection
                            if (Collection.class.isAssignableFrom(toPropertyType)) {

                                handleCollections(to, toClass, toPropertyType, fromFunction, finalFrom, toPropertyName);

                            } else {

                                Object value = eval(toPropertyType, fromFunction, finalFrom);

                                PropertyUtils.setNestedProperty(to, toPropertyName, value);
                            }

                        } catch (Exception e) {
                            log.warn("unable to set " + toPropertyName + " on " + toClass, e);
                        }

                    }
            );

            handlePostProcessor(to, toClass, from);


            return to;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void handleCollections(Object to, Class toClass, Class toPropertyType, Function fromFunction, Object finalFrom, String toPropertyName) throws Exception {

        Field field = toClass.getDeclaredField(toPropertyName);

        Type type = field.getGenericType();

        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Class typeForCollection = (Class) pt.getActualTypeArguments()[0];

            Object object = eval(toPropertyType, fromFunction, finalFrom);

            List fromList = null;

            MappingType overrideMappingTypeNested = null;

            if (object != null) {
                if (object instanceof CustomMappingObject) {
                    CustomMappingObject cmo = (CustomMappingObject) object;
                    overrideMappingTypeNested = cmo.getMappingType();
                    fromList = new ArrayList((Collection) cmo.getObject());
                } else {

                    fromList = new ArrayList((Collection) object);
                }
            }

            //Check to see if they are already providing toClass in the closure
            boolean isOverride = fromList != null && !fromList.isEmpty() && !typeForCollection.isAssignableFrom(fromList.iterator().next().getClass());


            if (fromList == null || fromList.isEmpty()) { //Set null if empty
                PropertyUtils.setProperty(to, toPropertyName, null);
            } else if (isOverride && mappingService.hasMappingForClass(typeForCollection)) { //Recursively call

                Object toValue = mapList(typeForCollection, fromList, overrideMappingTypeNested != null ? overrideMappingTypeNested : MappingType.MIN);

                PropertyUtils.setProperty(to, toPropertyName, toValue);


            } else { //Already provided by the fromList
                if (toPropertyType.isAssignableFrom(fromList.getClass())) { //Collection type is assignable

                    PropertyUtils.setProperty(to, toPropertyName, fromList);

                } else { //Explicit collection type
                    if (List.class.isAssignableFrom(toPropertyType)) {
                        List list = new ArrayList();
                        list.addAll(fromList);
                        PropertyUtils.setProperty(to, toPropertyName, list);

                    } else if (Set.class.isAssignableFrom(toPropertyType)) {
                        Set set = new HashSet();
                        set.addAll(fromList);
                        PropertyUtils.setProperty(to, toPropertyName, set);
                    } else {
                        throw new Exception("Unable to find find property collection type on " + toPropertyType);
                    }


                }


            }


        }
    }

    private void handlePostProcessor(Object to, Class toClass, Object from) throws Exception {
        Object mappingObject =  mappingService.getMappingClassObject(toClass);

        Class mappingClassClass = mappingObject.getClass();

        Method[] mappingMethods = mappingClassClass.getMethods();

        List<Class> classesList = new ArrayList<>();
        List<Object> objectList = new ArrayList<>();

        classesList.add(toClass);
        objectList.add(to);

        classesList.add(from.getClass());
        objectList.add(from);

        Class[] argumentClasses = new Class[classesList.size()];
        Object[] objectArray = new Object[objectList.size()];

        final Class[] finalArgumentClasses = classesList.toArray(argumentClasses);
        objectArray = objectList.toArray(objectArray);

        List<Method> methodsList = Arrays.stream(mappingMethods).filter(method -> method.isAnnotationPresent(PostProcessor.class) && Arrays.equals(method.getParameterTypes(), finalArgumentClasses))
                .collect(Collectors.toList());

        for (Method method : methodsList) {
            method.invoke(mappingObject, objectArray);
        }

    }

    @SuppressWarnings("unchecked")

    private <From> Object eval(Class toPropertyClass, Function fromExpression, From from) throws Exception {
        Object rhs = safelyEvaluateClosure(fromExpression, from);
        return (rhs != null && mappingService.hasMappingForClass(toPropertyClass) && !isAlreadyProvided(toPropertyClass, rhs)) ? doMapping(toPropertyClass, MappingType.MIN, "", rhs) : rhs;
    }

    @SuppressWarnings("unchecked")
    private boolean isAlreadyProvided(Class toPropertyClass, Object rhs) {
        return toPropertyClass.isAssignableFrom(rhs.getClass());
    }

    @SuppressWarnings("unchecked")
    private <From> Object safelyEvaluateClosure(Function fromExpression, From from) {
        try {

            return fromExpression.apply(from);

        } catch (NullPointerException npe) {
            return null;
        }

    }


}