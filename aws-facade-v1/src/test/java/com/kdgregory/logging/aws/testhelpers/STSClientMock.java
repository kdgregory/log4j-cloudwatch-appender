// Copyright (c) Keith D Gregory
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.kdgregory.logging.aws.testhelpers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.model.*;


/**
 *  Mocks the STS operations that we care about (at this point, only getCallerIdentity).
 */
public class STSClientMock
implements InvocationHandler
{
    /** The default account ID, when you don't specify one */
    public final static String DEFAULT_ACCOUNT_ID = "123456789012";

    // provided to constructor
    private String accountId;

    // invocation counts for known methods
    public int getCallerIdentityInvocationCount;


    /**
     *  Creates an instance with the default account ID.
     */
    public STSClientMock()
    {
        this(DEFAULT_ACCOUNT_ID);
    }


    /**
     *  Creates an instance with a specific account ID.
     */
    public STSClientMock(String accountId)
    {
        this.accountId = accountId;
    }

//----------------------------------------------------------------------------
//  Public methods
//----------------------------------------------------------------------------

    public AWSSecurityTokenService createClient()
    {
        return (AWSSecurityTokenService)Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] { AWSSecurityTokenService.class },
                                    STSClientMock.this);
    }

//----------------------------------------------------------------------------
//  Invocation Handler
//----------------------------------------------------------------------------

    /**
     *  The invocation handler; test code should not care about this.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
    {
        String methodName = method.getName();
        if (methodName.equals("getCallerIdentity"))
        {
            getCallerIdentityInvocationCount++;
            GetCallerIdentityRequest request = (GetCallerIdentityRequest)args[0];
            return getCallerIdentity(request);
        }

        // if nothing matches, fall through to here
        System.err.println("invocation handler called unexpectedly: " + methodName);
        throw new IllegalArgumentException("unexpected client call: " + methodName);
    }

//----------------------------------------------------------------------------
//  Default mock implementations
//----------------------------------------------------------------------------

    public GetCallerIdentityResult getCallerIdentity(GetCallerIdentityRequest request)
    {
        return new GetCallerIdentityResult().withAccount(accountId);
    }
}