package org.codehaus.tycho.eclipsepackaging.product;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;

@XStreamAlias("plugin")
public class Plugin {

	@XStreamAsAttribute
	private Boolean fragment;

	@XStreamAsAttribute
	private String id;

	@XStreamAsAttribute
	private String version;

	public Plugin() {
		super();
	}

	public Plugin(String id, String version) {
		this();
		this.id = id;
		this.version = version;
	}

	public Boolean getFragment() {
		return fragment;
	}

	public String getId() {
		return id;
	}

	public String getVersion() {
		return version;
	}

	public void setFragment(Boolean fragment) {
		this.fragment = fragment;
	}

	public void setId(String id) {
		this.id = id;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof Plugin))
			return false;
		Plugin other = (Plugin) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (version == null) {
			if (other.version != null)
				return false;
		} else if (!version.equals(other.version))
			return false;
		return true;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((version == null) ? 0 : version.hashCode());
		return result;
	}

}
