package org.reactome.idg.dao;

import java.util.List;

import org.reactome.idg.model.Provenance;

public interface ProvenanceDAO {

	public Provenance getProvenanceById(Integer id);
	
	public List<Provenance> getProvenanceByName(String name);
	
	/**
	 * Adds a Provenance object to the database. If there is an Provenance that matchs the
	 * passed provenance, the database version is returned. 
	 * @param A Provenance object that has been set up, but has no ID.
	 * @return The Provenance object that was created. All fields should have the same values as the input, but the ID should now be populated.
	 */
	public Provenance addProvenance(Provenance provenance);
	
}
