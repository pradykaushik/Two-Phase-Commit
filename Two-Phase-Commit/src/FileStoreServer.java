import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.apache.thrift.TException;

public class FileStoreServer implements FileStore.Iface{

	private String hostname, ipAddress;
	private int port;

	private static FileStoreServer singletonInstance = null;

	private static String parentDirectory = null;
	private static String archiveDirectory = null;
	private static String logFilePathString = null;

	private static HashMap<String, RFileMetadata> filesMap = new HashMap<String, RFileMetadata>();
	private static HashMap<String, HashMap<Operation, Boolean>> ongoingOperationFlagMap = new HashMap<String, HashMap<Operation,Boolean>>();
	private static Set<String> readyStateFiles = new HashSet<String>();
	private static final String COORDINATOR_HOSTNAME = "localhost";
	private static final String COORDINATOR_IPADDRESS = "127.0.0.1";
	private static final int COORDINATOR_PORT = 9090;

	private static final Map<Operation, Boolean> definedOperationFlagMap;

	static{
		Map<Operation, Boolean> definedOperationFlagInitializerMap = new HashMap<Operation, Boolean>();
		definedOperationFlagInitializerMap.put(Operation.READ, true);
		definedOperationFlagInitializerMap.put(Operation.WRITE, false);
		definedOperationFlagInitializerMap.put(Operation.DELETE, false);

		definedOperationFlagMap = Collections.unmodifiableMap(definedOperationFlagInitializerMap);
	}

	private static enum OperationStatus{
		LOCAL_COMMIT,
		GLOBAL_COMMIT,
		ABORT;
	}

	private FileStoreServer(String hostname, String ipAddress, int port) throws SystemException{
		if(hostname == null){
			SystemException systemException = new SystemException();
			systemException.setMessage("Hostname is null!");
			throw systemException;
		}
		if(ipAddress == null){
			SystemException systemException = new SystemException();
			systemException.setMessage("IpAddress is null!");
			throw systemException;
		}
		if(port < 0 && port > 65535){
			SystemException systemException = new SystemException();
			systemException.setMessage("Port number needs to be in the range [0,65535]!");
			throw systemException;
		}
		this.hostname = hostname;
		this.ipAddress = ipAddress;
		this.port = port;

		parentDirectory = hostname+"/live";
		archiveDirectory = hostname+"/archive";
		logFilePathString = hostname+".log";
		File parentDirectoryFile = new File(parentDirectory);
		File archiveDirectoryFile = new File(archiveDirectory);
		if(!parentDirectoryFile.exists()){
			if(parentDirectoryFile.mkdirs()){}
		}
		if(!archiveDirectoryFile.exists()){
			if(archiveDirectoryFile.mkdirs()){}
		}

		Map<String, FileOperation> inconsistentLog = getInconsistentLog();
		if(inconsistentLog != null){
			for(Map.Entry<String, FileOperation> inconsistentLogEntry : inconsistentLog.entrySet()){
				FileStore.Client coordinator = ServerFetcher.getServer(COORDINATOR_HOSTNAME, COORDINATOR_PORT);
				RecoveryInformation recoveryInformation = null;
				RFile rFile = null;
				try {
					recoveryInformation = coordinator.getRecoveryInformation(inconsistentLogEntry.getKey(), inconsistentLogEntry.getValue(), this.hostname, this.port);
					if(recoveryInformation.getStatus().equals(Status.SUCCESSFUL)){
						if(inconsistentLogEntry.getValue().getOperation().equals(Operation.WRITE)){
							rFile = recoveryInformation.getRFile();
							FileOutputStream liveFileOutputStream = null;
							FileOutputStream archiveFileOutputStream = null;
							byte[] contentBytes = rFile.getContent().getBytes();
							try{
								liveFileOutputStream = new FileOutputStream(parentDirectory+"/"+rFile.getMetadata().getFilename(), true);
								archiveFileOutputStream = new FileOutputStream(archiveDirectory+"/"+rFile.getMetadata().getFilename(), true);
								liveFileOutputStream.write(contentBytes);
								archiveFileOutputStream.write(contentBytes);
								writeLog(OperationStatus.GLOBAL_COMMIT, inconsistentLogEntry.getValue(), inconsistentLogEntry.getKey());
							}
							catch(FileNotFoundException fileNotFoundException){
								fileNotFoundException.printStackTrace();
							}
							catch(IOException ioException){
								ioException.printStackTrace();
							}
							finally{
								try{
									if(liveFileOutputStream != null){
										liveFileOutputStream.close();
									}
									if(archiveFileOutputStream != null){
										archiveFileOutputStream.close();
									}
								}
								catch(IOException exception){
									exception.printStackTrace();
								}
							}
						}
						else if(inconsistentLogEntry.getValue().getOperation().equals(Operation.DELETE)){
							rFile = recoveryInformation.getRFile();
							String fileToDeletePathString = rFile.getMetadata().getFilename();
							Path livePath = Paths.get(parentDirectory+"/"+fileToDeletePathString);
							Path archivePath = Paths.get(archiveDirectory+"/"+fileToDeletePathString);
							if(Files.exists(livePath)){
								try {
									Files.delete(livePath);
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
							if(Files.exists(archivePath)){
								try {
									Files.delete(archivePath);
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
						}
					}
					else{
						//rolling back to the archive directory
						try {
							File[] archiveFiles = archiveDirectoryFile.listFiles();
							Path destinationPath = null;
							for(File archiveFile : archiveFiles){
								destinationPath = archiveFile.toPath();
								Files.copy(archiveFile.toPath(), destinationPath);
							}
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				} catch (TException e) {
					e.printStackTrace();
				}
			}
		}

	}

	public static FileStoreServer getInstance(String hostname, String ipAddress, int port) throws SystemException{
		if(singletonInstance == null){
			singletonInstance = new FileStoreServer(hostname, ipAddress, port);
			return singletonInstance;
		}
		return singletonInstance;
	}

	private static Map<String, FileOperation> getInconsistentLog(){
		Path logFilePath = Paths.get(logFilePathString);
		if(!Files.exists(logFilePath)){
			new File(logFilePathString);
			return null;
		}
		else{
			BufferedReader bufferedReader = null;
			Stack<String> readStack = new Stack<String>();
			Stack<String> writeStack = new Stack<String>();
			Stack<String> deleteStack = new Stack<String>();
			String line = null;
			String[] lastLogComponents = null;
			String[] fileInfoLastLineLog = null;
			File logFile = new File(logFilePathString);
			try{
				bufferedReader = new BufferedReader(new FileReader(logFile));
				while((line = bufferedReader.readLine()) != null){
					lastLogComponents = line.split(":");
					fileInfoLastLineLog = lastLogComponents[1].split("\\s+");
					if(lastLogComponents[0].equals(OperationStatus.GLOBAL_COMMIT) || lastLogComponents[0].equals(OperationStatus.ABORT)){
						if(fileInfoLastLineLog[0].equals("READ")){
							if(!readStack.isEmpty()){
								readStack.pop();
							}
						}
						else if(fileInfoLastLineLog[0].equals("WRITE")){
							if(!writeStack.isEmpty()){
								writeStack.pop();
							}
						}
						else{
							//DELETE operation
							if(!deleteStack.isEmpty()){
								deleteStack.pop();
							}
						}
					}
					else{
						if(lastLogComponents[0].equals(OperationStatus.LOCAL_COMMIT)){
							if(fileInfoLastLineLog[0].equals("READ")){
								readStack.push(lastLogComponents[1]);
							}
							else if(fileInfoLastLineLog[0].equals("WRITE")){
								writeStack.push(lastLogComponents[1]);
							}
							else{
								//DELETE operation
								deleteStack.push(lastLogComponents[1]);
							}
						}
					}
				}
				/*
				 * now we check whether all of the stacks are empty
				 * if yes then return null
				 * else return all the values that are still present in the stacks
				 */
				if(readStack.isEmpty() && writeStack.isEmpty() && deleteStack.isEmpty()){
					return null;
				}
				else{
					Map<String, FileOperation> inconsistentMap = new HashMap<String, FileOperation>();
					if(!readStack.isEmpty()){
						while(!readStack.isEmpty()){
							FileOperation fileOperation = new FileOperation(Operation.READ);
							inconsistentMap.put(readStack.pop(), fileOperation);
						}
					}
					if(!writeStack.isEmpty()){
						while(!writeStack.isEmpty()){
							FileOperation fileOperation = new FileOperation(Operation.WRITE);
							inconsistentMap.put(writeStack.pop(), fileOperation);
						}
					}
					if(!deleteStack.isEmpty()){
						while(!deleteStack.isEmpty()){
							FileOperation fileOperation = new FileOperation(Operation.DELETE);
							inconsistentMap.put(deleteStack.pop(), fileOperation);
						}
					}
					return inconsistentMap;
				}
			}
			catch(FileNotFoundException fileNotFoundException){

			}
			catch(IOException ioException){

			}
			finally{
				try{
					if(bufferedReader != null){
						bufferedReader.close();
					}
				}
				catch(IOException ioException2){
					ioException2.printStackTrace();
				}
			}
			return null;
		}
	}

	@Override
	public StatusReport writeFile(RFile rFile) throws SystemException, TException {
		/*
		 * set operation flag to false
		 * checking whether file with the given name exists in the directory
		 * 	if yes then,
		 * 		overwrite file contents
		 * 		update update-time to current time
		 * 		increment version
		 * 		set new content length
		 * 		set new content hash
		 * 	else then,
		 * 		create new file in the directory
		 *		determine the metadata
		 *
		 * set operation flag to null
		 * return statusReport
		 */
		RFileMetadata metadataFromRFile = rFile.getMetadata();
		RFileMetadata fileMetadata = null;
		String filenameToWrite= metadataFromRFile.getFilename();
		String content = rFile.getContent();
		String contentHash = null;
		StatusReport statusReport = null;
		File liveFile = null, archiveFile = null;
		FileOutputStream liveFileOutputStream = null, archiveFileOutputStream = null;

		liveFile = new File(parentDirectory+"/"+filenameToWrite);
		archiveFile = new File(archiveDirectory+"/"+filenameToWrite);

		if(filesMap.containsKey(filenameToWrite)){
			try{
				//overwriting the content
				liveFileOutputStream = new FileOutputStream(liveFile, false);
				liveFileOutputStream.write(rFile.getContent().getBytes());
				liveFileOutputStream.flush();

				//need to overwrite the same content in the corresponding archive file also.
				archiveFileOutputStream = new FileOutputStream(archiveFile, false);
				archiveFileOutputStream.write(rFile.getContent().getBytes());
				archiveFileOutputStream.flush();

				//updating meta information
				fileMetadata = filesMap.get(filenameToWrite);
				fileMetadata.setUpdated(new Date().getTime());
				fileMetadata.setContentHash(metadataFromRFile.getContentHash());
				fileMetadata.setVersion(fileMetadata.getVersion()+1);
				fileMetadata.setContentLength(content.length());
				contentHash = new String(MessageDigest.getInstance("MD5").digest(rFile.getContent().getBytes()));
				fileMetadata.setContentHash(contentHash);

				//putting back the updated metadata
				filesMap.put(filenameToWrite, fileMetadata);

				//setting statusReport to be SUCCESSFUL
				statusReport = new StatusReport();
				statusReport.setStatus(Status.SUCCESSFUL);
			}
			catch(FileNotFoundException fileNotFoundException){
				SystemException systemException = new FileNotPresentException();
				systemException.setMessage(filenameToWrite+" not found!");
				throw systemException;
			}
			catch(IOException ioException){
				SystemException systemException = new FileNotPresentException();
				systemException.setMessage("Error opening file : "+filenameToWrite+"!");
				throw systemException;
			}
			catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
			}
			finally{
				try{
					if(liveFileOutputStream != null){
						liveFileOutputStream.close();
					}
					if(archiveFileOutputStream != null){
						archiveFileOutputStream.close();
					}
				}
				catch(IOException ioException2){
					SystemException systemException = new SystemException();
					systemException.setMessage("Error closing file : "+filenameToWrite+"!");
					throw systemException;
				}
			}
		}
		else{
			try{
				//creating new file and write contents
				liveFileOutputStream = new FileOutputStream(liveFile);
				liveFileOutputStream.write(rFile.getContent().getBytes());
				liveFileOutputStream.flush();

				//need to write the same contents to the archive file also
				archiveFileOutputStream = new FileOutputStream(archiveFile);
				archiveFileOutputStream.write(rFile.getContent().getBytes());
				archiveFileOutputStream.flush();

				//creating new meta information
				long created = new Date().getTime();
				contentHash = new String(MessageDigest.getInstance("MD5").digest(rFile.getContent().getBytes()));
				fileMetadata = createNewMetadata(created, created, 0, content.length(), contentHash, filenameToWrite);

				//putting back the updated metadata
				filesMap.put(filenameToWrite, fileMetadata);

				//setting statusReport to be SUCCESSFUL
				statusReport = new StatusReport();
				statusReport.setStatus(Status.SUCCESSFUL);
				setOperationFlag(filenameToWrite, Operation.WRITE, null);
				if(readyStateFiles.contains(filenameToWrite)){
					removeReadyStateFile(filenameToWrite);
				}
			}
			catch(FileNotFoundException fileNotFoundException){
				SystemException systemException = new FileNotPresentException();
				systemException.setMessage(filenameToWrite+" not found!");
				throw systemException;
			}
			catch(IOException ioException){
				SystemException systemException = new FileNotPresentException();
				systemException.setMessage("Error opening file : "+filenameToWrite+"!");
				throw systemException;
			}
			catch(NoSuchAlgorithmException noSuchAlgorithmException){
				SystemException systemException = new SystemException();
				systemException.setMessage("Error creating content hash!");
				throw systemException;
			}
			finally{
				try{
					if(liveFileOutputStream != null){
						liveFileOutputStream.close();
					}
					if(archiveFileOutputStream != null){
						archiveFileOutputStream.close();
					}
				}
				catch(IOException ioException2){
					SystemException systemException = new SystemException();
					systemException.setMessage("Error closing file : "+filenameToWrite+"!");
					throw systemException;
				}
			}
		}
		return statusReport;
	}

	@Override
	public RFile readFile(String filenameToRead) throws SystemException, TException {

		/*
		 * set operation flag to true
		 * checking whether a file with the given name exists within the directory
		 * 	if yes then we read in the contents of the file, set operation flag to be null and return it
		 * 	else we return FileNotPresentException
		 */
		RFileMetadata metadata;
		RFile rFile = new RFile();
		String content;
		StringBuilder contentBuilder = new StringBuilder("");
		File file;
		BufferedReader bufferedReader = null;
		String line;

		if(filesMap.containsKey(filenameToRead)){
			try{
				file = new File(parentDirectory+"/"+filenameToRead);
				metadata = filesMap.get(filenameToRead);
				bufferedReader = new BufferedReader(new FileReader(file));
				while((line = bufferedReader.readLine()) != null){
					contentBuilder.append(line+"\n");
				}
				contentBuilder.setLength(contentBuilder.length()-1);
				content = contentBuilder.toString();

				//setting metadata and content to RFile object
				rFile.setContent(content);
				rFile.setMetadata(metadata);
			}
			catch(FileNotFoundException fileNotFoundException){
				SystemException systemException = new FileNotPresentException();
				systemException.setMessage(filenameToRead+" not present!");
				throw systemException;
			}
			catch(IOException ioException){
				SystemException systemException = new SystemException();
				systemException.setMessage("Error opening file : "+filenameToRead);
				throw systemException;
			}
			finally{
				try{
					if(bufferedReader != null){
						bufferedReader.close();
					}
				}
				catch(IOException e){
					e.printStackTrace();
				}
			}
		}
		else{
			SystemException systemException = new FileNotPresentException();
			systemException.setMessage(filenameToRead+" does not exist!");
			throw systemException;
		}
		setOperationFlag(filenameToRead, Operation.READ, null);
		if(readyStateFiles.contains(filenameToRead)){
			removeReadyStateFile(filenameToRead);
		}
		return rFile;
	}

	@Override
	public StatusReport deleteFile(String filenameToDelete) throws SystemException,
	TException {
		/*
		 * set operation flag to false
		 * checking if the file is present in the directory
		 * 	if yes then,
		 * 		delete the file
		 * 		remove entry from filesMap
		 * 		remove entry from operationsMap
		 * 	else throw FileNotPresentException
		 * need to delete corresponding file from archive directory also.
		 * return statusReport
		 */
		File liveFile = null, archiveFile = null;
		StatusReport statusReport = new StatusReport();
		try{
			liveFile = new File(parentDirectory+"/"+filenameToDelete);
			archiveFile = new File(archiveDirectory+"/"+filenameToDelete);
			Files.delete(liveFile.toPath());
			Files.delete(archiveFile.toPath());
			filesMap.remove(filenameToDelete);
			removeOperationFlagEntry(filenameToDelete);
			statusReport.setStatus(Status.SUCCESSFUL);
		}
		catch(IOException ioException){
			SystemException systemException = new FileNotPresentException();
			systemException.setMessage(filenameToDelete+" not found!");
			throw systemException;
		}
		if(readyStateFiles.contains(filenameToDelete)){
			removeReadyStateFile(filenameToDelete);
		}
		//need to remove file from archive directory
		return statusReport;
	}

	@Override
	public StatusReport doVote(RFile rFile, FileOperation fileOperation)
			throws SystemException, TException {
		Operation operation = fileOperation.getOperation();
		StatusReport statusReport = new StatusReport();
		String filename = rFile.getMetadata().getFilename();
		/*
		 * checking if file not currently in ready state.
		 * 	If not then,
		 * 		vote commit
		 * 	else we need to perform additional check for conflicting operations.
		 */
		synchronized (filesMap) {
			if(!filesMap.containsKey(filename)){
				/*
				 * the file is not present in the directory.
				 * if the operation is delete, then abort
				 * else commit and add entry to the filesMap and ongoingOperationFlag
				 */
				if(Operation.DELETE.equals(fileOperation) || Operation.READ.equals(fileOperation)){
					statusReport.setStatus(Status.FAILED);
					//write vote-abort to log
					writeLog(OperationStatus.ABORT, fileOperation, filename);
				}
				else{
					statusReport.setStatus(Status.SUCCESSFUL);
					filesMap.put(filename, rFile.getMetadata());
					HashMap<Operation, Boolean> ongoingOperationEntry = new HashMap<Operation, Boolean>();
					ongoingOperationEntry.put(operation, false);
					ongoingOperationFlagMap.put(filename, ongoingOperationEntry);
					readyStateFiles.add(filename);
					//write vote-commit to log
					writeLog(OperationStatus.LOCAL_COMMIT, fileOperation, filename);
				}
			}
			else{
				/*
				 * If file not in ready state then we can vote commit
				 * else we need to check for corresponding conflicting operations.
				 */
				if(!readyStateFiles.contains(filename)){
					statusReport.setStatus(Status.SUCCESSFUL);
					filesMap.put(filename, rFile.getMetadata());
					if(Operation.WRITE.equals(operation) || Operation.DELETE.equals(operation)){
						ongoingOperationFlagMap.get(filename).put(operation, false);
					}
					else{
						ongoingOperationFlagMap.get(filename).put(operation, true);
					}
					readyStateFiles.add(filename);
					//write vote-commit to log
					writeLog(OperationStatus.LOCAL_COMMIT, fileOperation, filename);
				}
				else{
					Boolean ongoingOperationFlag;
					if(!ongoingOperationFlagMap.get(filename).containsKey(operation) || (ongoingOperationFlag = ongoingOperationFlagMap.get(filename).get(operation)) == null){
						statusReport.setStatus(Status.SUCCESSFUL);
						if(Operation.WRITE.equals(operation) || Operation.DELETE.equals(operation)){
							ongoingOperationFlagMap.get(filename).put(operation, false);
						}
						else{
							ongoingOperationFlagMap.get(filename).put(operation, true);
						}
						readyStateFiles.add(filename);
						//write vote-commit to log
						writeLog(OperationStatus.LOCAL_COMMIT, fileOperation, filename);
					}
					else{
						if(ongoingOperationFlagMap.get(filename).containsKey(operation) && ((ongoingOperationFlag = ongoingOperationFlagMap.get(filename).get(operation)) && definedOperationFlagMap.get(operation))){
							statusReport.setStatus(Status.SUCCESSFUL);
							if(Operation.WRITE.equals(operation) || Operation.DELETE.equals(operation)){
								ongoingOperationFlagMap.get(filename).put(operation, false);
							}
							else{
								ongoingOperationFlagMap.get(filename).put(operation, true);
							}
							readyStateFiles.add(filename);
							//write vote-commit to log
							writeLog(OperationStatus.LOCAL_COMMIT, fileOperation, filename);
						}
						else{
							statusReport.setStatus(Status.FAILED);
							//write vote-abort to log
							writeLog(OperationStatus.ABORT, fileOperation, filename);
						}
					}
				}

			}

		}
		return statusReport;

	}
	/*
	 * we need to perform the corresponding operation on the file
	 * if operation is READ then,
	 * 		call readFile and pass the filename as argument
	 * else if operation is WRITE then,
	 * 		call writeFile and pass the rFIle object as argument
	 * else call deleteFile and pass the filename as argument
	 */
	@Override
	public RFile doCommitRead(String filenameToRead) throws SystemException,
	TException {
		/*
		 * write global-commit to log.
		 * call readFile method and pass the filename as the argument.
		 * set corresponding ongoingOperationFlag to null.
		 * remove corresponding ready state file entry.
		 * return the RFile object returned from the readFile method.
		 */
		writeLog(OperationStatus.GLOBAL_COMMIT, new FileOperation(Operation.READ), filenameToRead);
		RFile rFile = readFile(filenameToRead);
		setOperationFlag(filenameToRead, Operation.READ, null);
		removeReadyStateFile(filenameToRead);
		return rFile;
	}

	@Override
	public StatusReport doCommitWrite(RFile rFile) throws SystemException,
	TException {
		/*
		 * write global-commit to log.
		 * call writeFile and pass rFile as the argument.
		 * set corresponding ongoingOperationFlag to null.
		 * remove corresponding ready state file entry.
		 * return the statusReport returned by writeFile method.
		 */
		writeLog(OperationStatus.GLOBAL_COMMIT, new FileOperation(Operation.WRITE), rFile.getMetadata().getFilename());
		StatusReport statusReport = writeFile(rFile);
		setOperationFlag(rFile.getMetadata().getFilename(), Operation.WRITE, null);
		removeReadyStateFile(rFile.getMetadata().getFilename());
		return statusReport;
	}

	@Override
	public StatusReport doCommitDelete(String filenameToDelete)
			throws SystemException, TException {
		/*
		 * write global=commit to log.
		 * call deleteFile and pass the filename as argument.
		 * set corresponding ongoingOperationFlag to null.
		 * remove corresponding ready state file entry.
		 * return the statusReport returned by deleteFile method.
		 */
		writeLog(OperationStatus.GLOBAL_COMMIT, new FileOperation(Operation.DELETE), filenameToDelete);
		StatusReport statusReport = deleteFile(filenameToDelete);
		removeReadyStateFile(filenameToDelete);
		return statusReport;
	}


	@Override
	public StatusReport doAbort(RFile rFile, FileOperation fileOperation)
			throws SystemException, TException {
		/*
		 * write global-abort to log.
		 * remove corresponding ready state file entry if present (this will only be the case if voted YES).
		 * return a successful status report.
		 */
		writeLog(OperationStatus.ABORT, fileOperation, rFile.getMetadata().getFilename());
		String filename;
		if(readyStateFiles.contains(filename = rFile.getMetadata().getFilename())){
			removeReadyStateFile(filename);
		}
		return new StatusReport(Status.SUCCESSFUL);

	}

	private synchronized RFileMetadata createNewMetadata(long createdTime, long updatedTime, int version, int contentLength, String contentHash, String filename){

		RFileMetadata fileMetadata = new RFileMetadata();
		fileMetadata.setCreated(createdTime);
		fileMetadata.setUpdated(updatedTime);
		fileMetadata.setVersion(version);
		fileMetadata.setContentLength(contentLength);
		fileMetadata.setContentHash(contentHash);
		fileMetadata.setFilename(filename);

		return fileMetadata;
	}

	private static synchronized void setOperationFlag(String filename, Operation operation, Boolean flag){
		ongoingOperationFlagMap.get(filename).put(operation, flag);
	}

	private static synchronized void removeOperationFlagEntry(String filenameToRemove){
		ongoingOperationFlagMap.remove(filenameToRemove);
	}

	private static synchronized void removeReadyStateFile(String filename){
		readyStateFiles.remove(filename);
	}

	private static synchronized void writeLog(OperationStatus operationStatus, FileOperation fileOperation, String filename) throws SystemException{
		StringBuilder logBuilder = new StringBuilder("");
		logBuilder.append(operationStatus.toString());
		logBuilder.append(":");
		logBuilder.append(fileOperation.getOperation().toString());
		logBuilder.append(" ");
		logBuilder.append(filename);
		logBuilder.append("\n");

		File logFile = new File(logFilePathString);
		FileOutputStream fileOutputStream = null;
		try{
			fileOutputStream = new FileOutputStream(logFile, true);
			fileOutputStream.write(logBuilder.toString().getBytes());
			fileOutputStream.flush();
		}
		catch(FileNotFoundException fileNotFoundException){
			SystemException systemException = new FileNotPresentException();
			systemException.setMessage(filename+" does not exist!");
			throw systemException;
		}
		catch(IOException ioException){
			SystemException systemException = new SystemException();
			systemException.setMessage("Error in logging!");
			throw systemException;
		}
		finally{
			try{
				if(fileOutputStream != null){
					fileOutputStream.close();
				}
			}
			catch(IOException exception){
				SystemException systemException = new SystemException();
				systemException.setMessage("Error occured while closing the output stream to file "+filename);
				throw systemException;
			}
		}
	}

	public void setHostname(String hostname) throws SystemException{
		if(hostname == null){
			SystemException systemException = new SystemException();
			systemException.setMessage("Hostname is null!");
			throw systemException;
		}
		this.hostname = hostname;
	}

	public void setIpAddress(String ipAddress) throws SystemException{
		if(ipAddress == null){
			SystemException systemException = new SystemException();
			systemException.setMessage("IpAddress is null!");
			throw systemException;
		}
		this.ipAddress = ipAddress;
	}

	public void setPort(int port) throws SystemException{
		if(port < 0 && port > 65535){
			SystemException systemException = new SystemException();
			systemException.setMessage("Port number needs to be in the range [0,65535]!");
			throw systemException;
		}
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

	@Override
	public RecoveryInformation getRecoveryInformation(String filename,
			FileOperation fileOperation, String hostname, int port) throws SystemException, TException {
		return null;
	}

}
