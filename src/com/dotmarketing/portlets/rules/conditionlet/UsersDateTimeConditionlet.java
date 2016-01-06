package com.dotmarketing.portlets.rules.conditionlet;

import com.dotcms.repackage.org.apache.logging.log4j.util.Strings;
import com.dotmarketing.portlets.rules.RuleComponentInstance;
import com.dotmarketing.portlets.rules.exception.ComparisonNotPresentException;
import com.dotmarketing.portlets.rules.exception.ComparisonNotSupportedException;
import com.dotmarketing.portlets.rules.model.ParameterModel;
import com.dotmarketing.portlets.rules.parameter.ParameterDefinition;
import com.dotmarketing.portlets.rules.parameter.comparison.Comparison;
import com.dotmarketing.portlets.rules.parameter.display.TextInput;
import com.dotmarketing.portlets.rules.parameter.type.TextType;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

import static com.dotcms.repackage.com.google.common.base.Preconditions.checkState;
import static com.dotmarketing.portlets.rules.parameter.comparison.Comparison.*;

/**
 * This conditionlet will allow dotCMS users to check the client's current date
 * and time when a page was requested. This {@link Conditionlet} provides a
 * drop-down menu with the available comparison mechanisms, and a text field to
 * enter the date and time to compare. This date/time parameter will be
 * expressed in milliseconds in order to avoid any format-related and time zone
 * issues.
 * <p>
 * The date and time of the request is determined by the IP address of the
 * client that issued the request. Geographic information is then retrieved via
 * the <a href="http://maxmind.github.io/GeoIP2-java/index.html">GeoIP2 Java
 * API</a>.
 *
 * @author Jose Castro
 * @version 1.0
 * @since 04-22-2015
 */

public class UsersDateTimeConditionlet extends Conditionlet<UsersDateTimeConditionlet.Instance> {

    private static final long serialVersionUID = 1L;

    public static final String REFERRING_URL_KEY = "referring-url";

    private static final ParameterDefinition<TextType> referringURLValue = new ParameterDefinition<>(
            3, REFERRING_URL_KEY,
            new TextInput<>(new TextType())
    );

    public UsersDateTimeConditionlet() {
        super("api.ruleengine.system.conditionlet.VisitorsDateTime",
                new ComparisonParameterDefinition(2, EQUAL, BETWEEN, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL),
                referringURLValue);
    }

    @Override
    public boolean evaluate(HttpServletRequest request, HttpServletResponse response, Instance instance) {
        String referringUrlActualValue = request.getHeader("referer");

        if (Strings.isBlank(referringUrlActualValue)) {
            return false;
        }

        boolean evalSuccess;

        if (instance.comparison != REGEX) {
            //noinspection unchecked
            evalSuccess = instance.comparison.perform(referringUrlActualValue.toLowerCase(), instance.referringURLValue.toLowerCase());
        } else {
            evalSuccess = REGEX.perform(referringUrlActualValue, instance.referringURLValue);
        }
        return evalSuccess;
    }

    @Override
    public Instance instanceFrom(Map<String, ParameterModel> parameters) {
        return new Instance(this, parameters);
    }

    public static class Instance implements RuleComponentInstance {

        public final String referringURLValue;
        public final Comparison<String> comparison;

        public Instance(Conditionlet definition, Map<String, ParameterModel> parameters) {
            checkState(parameters != null && parameters.size() == 2, "Referring URL Condition requires parameters %s and %s.", COMPARISON_KEY, REFERRING_URL_KEY);
            assert parameters != null;
            this.referringURLValue = parameters.get(REFERRING_URL_KEY).getValue();
            String comparisonValue = parameters.get(COMPARISON_KEY).getValue();
            try {
                //noinspection unchecked
                this.comparison = ((ComparisonParameterDefinition) definition.getParameterDefinitions().get(COMPARISON_KEY)).comparisonFrom(comparisonValue);
            } catch (ComparisonNotPresentException e) {
                throw new ComparisonNotSupportedException("The comparison '%s' is not supported on Condition type '%s'",
                        comparisonValue,
                        definition.getId());
            }
        }
    }
}
