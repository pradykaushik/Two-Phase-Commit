typedef i64 Timestamp

exception SystemException {
  1: optional string message
}

enum Status{
  FAILED = 0;
  SUCCESSFUL = 1;
}

enum Operation{
  READ = 0;
  WRITE = 1;
  DELETE = 2;
}

struct StatusReport{
  1: required Status status;
}

struct FileOperation{
  1: required Operation operation;
}

struct RecoveryInformation{
  1: required Status status;
  2: required RFile rFile;
}

struct RFileMetadata{
  1: optional string filename;
  2: optional string contentHash;
  3: optional Timestamp created;
  4: optional Timestamp updated;
  5: optional i32 version;
  6: optional i32 contentLength;
}

struct RFile{
  1: optional RFileMetadata metadata;
  2: optional string content;
}

service FileStore{
  StatusReport writeFile(1: RFile rFile)
    throws (1: SystemException systemException),

  RFile readFile(1: string filenameToRead)
    throws (1: SystemException systemException),

  StatusReport deleteFile(1: string filenameToDelete)
    throws (1: SystemException systemException),

  StatusReport doVote(1: RFile rFile, 2: FileOperation fileOperation)
    throws (1: SystemException systemException),

  RFile doCommitRead(1: string filenameToRead)
    throws (1: SystemException systemException),

  StatusReport doCommitWrite(1: RFile rFile)
    throws (1: SystemException systemException),

  StatusReport doCommitDelete(1: string filenameToDelete)
    throws (1: SystemException systemException),

  StatusReport doAbort(1: RFile rFile, 2: FileOperation fileOperation)
    throws (1: SystemException systemException),

  RecoveryInformation getRecoveryInformation(1: string filename, 2: FileOperation fileOperation, 3: string hostname, 4: i32 port)
    throws (1: SystemException systemException),
}
