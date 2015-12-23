import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;

public class ServerFetcher {

	public static FileStore.Client getServer(String hostname, int port){
		TSocket tSocket = null;
		TIOStreamTransport tioStreamTransport = null;
		TProtocol tProtocol = null;
		FileStore.Client client = null;
		try{

			tSocket = new TSocket(hostname, port);
			tioStreamTransport = tSocket;
			tioStreamTransport.open();

			tProtocol = new TBinaryProtocol(tioStreamTransport);
			client = new FileStore.Client(tProtocol);
		}
		catch(TTransportException e){
			if(tSocket != null){
				tSocket.close();
			}
			return null;
		}
		return client;
	}
}
