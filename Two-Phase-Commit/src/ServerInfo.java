public class ServerInfo {

	private String hostname;
	private String ipAddress;
	private int port;
	
	public ServerInfo(String hostname, String ipAddress, int port){
		this.hostname = hostname;
		this.ipAddress = ipAddress;
		this.port = port;
	}
	
	public ServerInfo() {}
	
	public void setHostname(String hostname){
		this.hostname = hostname;
	}
	
	public void setIpAddress(String ipAddress){
		this.ipAddress = ipAddress;
	}
	
	public void setPort(int port){
		this.port = port;
	}
	
	public String getHostname(){
		return this.hostname;
	}
	
	public String getIpAddress(){
		return this.ipAddress;
	}
	
	public int getPort(){
		return this.port;
	}
	
	public String toString(){
		StringBuilder stringBuilder = new StringBuilder("");
		stringBuilder.append("HOSTNAME : ");
		stringBuilder.append(this.hostname);
		stringBuilder.append("\nIP : ");
		stringBuilder.append(this.ipAddress);
		stringBuilder.append("\nPORT : ");
		stringBuilder.append(this.port);
		stringBuilder.append("\n");
		return stringBuilder.toString();
	}
	
	@Override
	public boolean equals(Object obj) {
		if(!(obj instanceof ServerInfo)){
			return false;
		}
		else{
			ServerInfo serverInfo = (ServerInfo)obj;
			if(this.hostname.equals(serverInfo.getHostname()) && this.port == serverInfo.getPort()){
				return true;
			}
			return false;
		}
	}
	
	@Override
	public int hashCode() {
		return this.toString().hashCode();
	}
}
