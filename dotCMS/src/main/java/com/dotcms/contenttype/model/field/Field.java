package com.dotcms.contenttype.model.field;

import java.io.Serializable;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.elasticsearch.common.Nullable;
import org.immutables.value.Value;
import org.immutables.value.Value.Derived;

import com.dotcms.contenttype.model.component.FieldFormRenderer;
import com.dotcms.contenttype.model.component.FieldValueRenderer;
import com.dotcms.repackage.com.google.common.base.Preconditions;
import com.dotcms.repackage.com.google.common.collect.ImmutableList;
import com.dotcms.repackage.org.apache.commons.lang.time.DateUtils;
import com.dotmarketing.business.DotStateException;
import com.dotmarketing.business.FactoryLocator;
import com.dotmarketing.exception.DotDataException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;

@JsonTypeInfo(
	use = Id.CLASS,
	include = JsonTypeInfo.As.PROPERTY,
	property = "clazz"
)
@JsonSubTypes({
	@Type(value = BinaryField.class),
	@Type(value = CategoryField.class),
	@Type(value = CheckboxField.class),
	@Type(value = ConstantField.class),
	@Type(value = CustomField.class),
	@Type(value = DateField.class),
	@Type(value = DateTimeField.class),
	@Type(value = EmptyField.class),
	@Type(value = FileField.class),
	@Type(value = HiddenField.class),
	@Type(value = HostFolderField.class),
	@Type(value = ImageField.class),
	@Type(value = KeyValueField.class),
	@Type(value = LineDividerField.class),
	@Type(value = MultiSelectField.class),
	@Type(value = PermissionTabField.class),
	@Type(value = RadioField.class),
	@Type(value = RelationshipsTabField.class),
	@Type(value = SelectField.class),
	@Type(value = TabDividerField.class),
	@Type(value = TagField.class),
	@Type(value = TextAreaField.class),
	@Type(value = TextField.class),
	@Type(value = TimeField.class),
	@Type(value = WysiwygField.class),
})
public abstract class Field implements FieldIf, Serializable {

  @Value.Check
  public void check() {
    if (iDate().after(legacyFieldDate)) {
      Preconditions.checkArgument(acceptedDataTypes().contains(dataType()),
          this.getClass().getSimpleName() + " must have DataType:" + acceptedDataTypes());
    }
  }


  private static final long serialVersionUID = 5640078738113157867L;
  final static Date legacyFieldDate = new Date(1470845479000L); // 08/10/2016 @ 4:11pm (UTC)

  @Value.Default
  public boolean searchable() {
    return false;
  }

  @Value.Default
  public boolean unique() {
    return false;
  }

  @Value.Default
  public boolean indexed() {
    return false;
  }

  @Value.Default
  public boolean listed() {
    return false;
  }

  @Value.Default
  public boolean readOnly() {
    return false;
  }

  @Nullable
  public abstract String owner();

  @Nullable
  public abstract String id();


  @Value.Lazy
  public String inode() {
    return id();
  }

  @Value.Default
  public Date modDate() {
    return DateUtils.round(new Date(), Calendar.SECOND);
  }


  public abstract String name();

  @JsonIgnore
  @Derived
  public String typeName() {
    return LegacyFieldTypes.getImplClass(this.getClass().getCanonicalName()).getCanonicalName();
  }

  @JsonIgnore
  @Derived
  public Class<Field> type() {
    return LegacyFieldTypes.getImplClass(this.getClass().getCanonicalName());
  }

  @Nullable
  public abstract String relationType();

  @Value.Default
  public boolean required() {
    return false;
  }

  @Nullable
  public abstract String variable();

  @Value.Default
  public int sortOrder() {
    return -1;
  }

  @Value.Lazy
  public List<SelectableValue> selectableValues() {
    return ImmutableList.of();
  };


  @Nullable
  public abstract String values();

  @Nullable
  public abstract String regexCheck();

  @Nullable
  public abstract String hint();

  @Nullable
  public abstract String defaultValue();


  @Value.Default
  public boolean fixed() {
    return false;
  }

  public boolean legacyField() {
    return false;
  }

  @Value.Lazy
  public List<FieldVariable> fieldVariables() {
    if (innerFieldVariables == null) {
      try {
        //System.err.println("loading field.variables:" + this.variable() + ":"+ System.identityHashCode(this));
        innerFieldVariables = FactoryLocator.getFieldFactory().loadVariables(this);
      } catch (DotDataException e) {
        throw new DotStateException("unable to load field variables:" + e.getMessage(), e);
      }
    }

    return innerFieldVariables;

  }

  private List<FieldVariable> innerFieldVariables = null;

  public void constructFieldVariables(List<FieldVariable> fieldVariables) {

    innerFieldVariables = fieldVariables;
  }

  @JsonIgnore
  public abstract List<DataTypes> acceptedDataTypes();

  public abstract DataTypes dataType();

  @Nullable
  public abstract String contentTypeId();
  
  @Nullable
  @Value.Auxiliary 
  public abstract String dbColumn();

  @Value.Default
  public Date iDate() {
    return DateUtils.round(new Date(), Calendar.SECOND);

  }



  @Value.Lazy
  public FieldFormRenderer formRenderer() {
    return new FieldFormRenderer() {};
  }

  @Value.Lazy
  public FieldValueRenderer valueRenderer() {
    return new FieldValueRenderer() {};
  }


  @Value.Lazy
  public FieldValueRenderer listRenderer() {
    return new FieldValueRenderer() {};
  }
}
