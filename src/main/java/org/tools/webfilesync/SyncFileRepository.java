package org.tools.webfilesync;

import java.util.Date;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncFileRepository  extends JpaRepository<SyncFile, Long> {

	SyncFile findOneByPathAndFileurl(String path,String fileurl);
	SyncFile findOneByRelPathAndNameAndFileurl(String path,String name,String fileurl);
	List<SyncFile> findAllByOpAndFileurl(String op,String fileurl);
	List<SyncFile> findAllByLastCheckedNotAndFileurl(Date lastChecked,String fileurl);
	List<SyncFile> findAllByLastCheckedNotAndOpNotAndFileurl(Date lastChecked,String op,String fileurl);
}
