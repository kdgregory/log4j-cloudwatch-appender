// Copyright (c) Keith D Gregory, all rights reserved
package com.kdgregory.log4j.shared;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import com.amazonaws.util.EC2MetadataUtils;


/**
 *  Handles the standard substitution variables.
 *  <p>
 *  This is an instantiable class to support testing: the default constructor
 *  uses "real" values (eg, the current date), while the base constructor allows
 *  the caller to specify test values.
 *  <p>
 *  Instances are thread-safe.
 */
public class Substitutions
{
    private String date;
    private String timestamp;
    private String startupTimestamp;
    private String pid;
    private String hostname;


    /**
     *  Constructs an instance with specified substitution values.
     */
    public Substitutions(Date curremtDate)
    {
        RuntimeMXBean runtimeMx = ManagementFactory.getRuntimeMXBean();
        String vmName = runtimeMx.getName();

        pid = (vmName.indexOf('@') > 0)
            ? vmName.substring(0, vmName.indexOf('@'))
            : "unknown";

        hostname = (vmName.indexOf('@') > 0)
                 ? vmName.substring(vmName.indexOf('@') + 1, vmName.length())
                 : "unknown";

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        date = dateFormat.format(curremtDate);

        SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        timestampFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        timestamp = timestampFormat.format(curremtDate);
        startupTimestamp = timestampFormat.format(new Date(runtimeMx.getStartTime()));
    }


    /**
     *  Constructs an instance with real substitution values.
     */
    public Substitutions()
    {
        this(new Date());
    }


    /**
     *  Applies all substitutions.
     */
    public String perform(String input)
    {
        String output = input;
        do
        {
            input = output;
            output = substitute("{date}",            date,
                     substitute("{timestamp}",       timestamp,
                     substitute("{startupTimestamp}", startupTimestamp,
                     substitute("{pid}",             pid,
                     substitute("{hostname}",        hostname,
                     substituteInstanceId(
                     substituteSysprop(
                     substituteEnvar(
                     input))))))));
        }
        while (! output.equals(input));
        return output;
    }


    /**
     *  Performs simple subsitutions, where the tag fully describes the substitution.
     */
    private String substitute(String tag, String value, String input)
    {
        int index = input.indexOf(tag);
        return (index >= 0)
             ? input.substring(0, index) + value + input.substring(index + tag.length(), input.length())
             : input;
    }



    /**
     *  Substitutes the EC2 instance ID. If not running on EC2 we won't be able
     *  to retrieve instance metadata (and it takes a long time to learn that,
     *  waiting for a timeout) so this isn't handled as a "simple" substitution.
     */
    private String substituteInstanceId(String input)
    {
        int index = input.indexOf("{instanceId}");
        if (index < 0)
            return input;

        String instanceId = EC2MetadataUtils.getInstanceId();
        if ((instanceId == null) || (instanceId.length() == 0))
            return input;

        return substitute("{instanceId}", instanceId, input);
    }


    /**
     *  Substitutes system properties, where the property depends on the tag.
     */
    private String substituteSysprop(String input)
    {
        int index = input.indexOf("{sysprop:");
        if (index < 0)
            return input;

        int index2 = input.indexOf('}', index);
        if (index2 < 0)
            return input;

        String propName = input.substring(index + 9, index2);
        String propValue = System.getProperty(propName);
        if (propValue == null)
            return input;

        return input.substring(0, index) + sanitize(propValue) + input.substring(index2 + 1, input.length());
    }


    /**
     *  Substitutes environment variables, where the variable depends on the tag.
     */
    private String substituteEnvar(String input)
    {
        int index = input.indexOf("{env:");
        if (index < 0)
            return input;

        int index2 = input.indexOf('}', index);
        if (index2 < 0)
            return input;

        String propName = input.substring(index + 5, index2);
        String propValue = System.getenv(propName);
        if (propValue == null)
            return input;

        return input.substring(0, index) + sanitize(propValue) + input.substring(index2 + 1, input.length());
    }

    /**
     *  Restricts the substitution value to a limited alphabet.
     */
    private String sanitize(String value)
    {
        return value.replaceAll("[^A-Za-z0-9-_]", "");
    }
}
