package org.tools.webfilesync;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import lombok.Data;

@Entity
@Data
@Table(name = "sync_file")
public class SyncFile {
	
	@Id
	@GeneratedValue(strategy=GenerationType.IDENTITY)
	private Long id;	
	@Column(length = 500)
	private String fileurl;
	private String name;
	@Column(length = 1000)
	private String path;
	@Column(length = 1000)
	private String folderPath;
	@Column(length = 1000)
	private String relPath;
	private Long lastUpdate;
	private Date lastChecked;
	private String op;
	private Long size;
}
