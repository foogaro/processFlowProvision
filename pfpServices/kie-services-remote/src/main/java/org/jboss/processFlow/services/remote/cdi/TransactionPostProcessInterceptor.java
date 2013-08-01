package org.jboss.processFlow.services.remote.cdi;

import javax.transaction.Status;
import javax.ws.rs.ext.Provider;

import org.jboss.resteasy.annotations.interception.ServerInterceptor;
import org.jboss.resteasy.core.ServerResponse;
import org.jboss.resteasy.spi.interception.PostProcessInterceptor;

@Provider
@ServerInterceptor
public class TransactionPostProcessInterceptor extends BaseInterceptor implements PostProcessInterceptor{
	
	@Override
	public void postProcess(ServerResponse sResponse) {
		try {
			if(tMgr.getStatus() == Status.STATUS_ACTIVE)
				tMgr.commit();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
