package org.reactome.idg.mapping;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactome.idg.util.UniprotFileRetriever;
import org.reactome.idg.util.UniprotFileRetriever.UniprotDB;

public class MapToHuman
{
	private static final Logger logger = LogManager.getLogger(MapToHuman.class);
	private static final String HUMAN = "HUMAN";
	private static final String PATH_TO_DATA_FILES = "src/main/resources/data/";
	private static final String PPIS_MAPPED_TO_HUMAN_FILE = "PPIS_mapped_to_human.txt";

	private String outputPath = "";
	private static URI uniprotMappingServiceURI = null;

	private String stringDBSpeciesCode;

	public MapToHuman() throws URISyntaxException
	{
		try
		{
			// uniprotMappingServiceURI only needs to be initialized once. But because new URI could throw URISyntaxException,
			// it cannot be initialized in a static initializer.
			synchronized (this)
			{
				if (uniprotMappingServiceURI == null)
				{
					uniprotMappingServiceURI = new URI("https://www.uniprot.org/uploadlists/");
				}
			}
		}
		catch (URISyntaxException e)
		{
			e.printStackTrace();
			throw e;
		}
	}

	public static void main(String[] args) throws IOException, URISyntaxException
	{
		MapToHuman mapper = new MapToHuman();
//		mapper.stringDBSpeciesCode = "4932";
//		mapper.mapOtherSpeciesToHuman("YEAST", true);
//		mapper.stringDBSpeciesCode = "4896";
//		mapper.mapOtherSpeciesToHuman("SCHPO", true);
		mapper.stringDBSpeciesCode = "4932";
		mapper.generateOtherSpeciesToHumanPPIs("YEAST");

	}

	enum ProteinIdentifierType
	{
		STRINGDB, UNIPROT_GENE, UNIPROT_ACCESSION, SGD
	}

	class Protein implements Comparable<Protein>
	{
		private String identifierValue;
		ProteinIdentifierType identifierType;
		public Protein(String value, ProteinIdentifierType type)
		{
			this.identifierValue = value;
			this.identifierType = type;
		}

		@Override
		public String toString()
		{
			// Don't want identifierType in string representation of protein. Maybe have a "detailedToString" that could include it.
			return this.identifierValue;
		}

		@Override
		public int hashCode()
		{
			return super.hashCode();
		}

		@Override
		public boolean equals(Object other)
		{
			if (other == null)
			{
				return false;
			}
			if (other instanceof Protein)
			{
				return (this.identifierValue == ((Protein)other).identifierValue && this.identifierType == ((Protein)other).identifierType);
			}
			return other.equals(this);
		}

		@Override
		public int compareTo(Protein protein2)
		{
			return this.identifierValue.compareTo(protein2.identifierValue);
		}

		public String getIdentifierValue()
		{
			return identifierValue;
		}

		public ProteinIdentifierType getIdentifierType()
		{
			return identifierType;
		}
	}

	class Ppi implements Comparable<Ppi>
	{
		private Protein protein1;
		private Protein protein2;

		public Ppi(Protein protein1, Protein protein2)
		{
			this.protein1 = protein1;
			this.protein2 = protein2;
		}

		@Override public String toString()
		{
			return this.protein1.compareTo(this.protein2) < 0
					? this.protein1 + "\t" + this.protein2
					: this.protein2 + "\t" + this.protein1;
		}

		@Override
		public int hashCode()
		{
			return this.toString().hashCode();
		}

		@Override
		public boolean equals(Object other)
		{
			if (other == null)
			{
				return false;
			}
			return this.toString().equals(other.toString());
		}

		public Protein getProtein1()
		{
			return protein1;
		}

		public Protein getProtein2()
		{
			return protein2;
		}

		@Override
		public int compareTo(Ppi other)
		{
			return this.toString().compareTo(other.toString());
		}
	}

	private Set<Ppi> getBindingPPIs(String stringDBActionsFile)
	{
		Set<Ppi> ppis = new HashSet<>();
		String bindingPPIs = outputPath + this.stringDBSpeciesCode + "_binding_PPIs.tsv";
		try(FileReader reader = new FileReader(stringDBActionsFile);
			CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT
				.withDelimiter('\t')
				.withFirstRecordAsHeader());
			FileWriter writer = new FileWriter(bindingPPIs);)
		{
			List<CSVRecord> records = parser.getRecords();
			for (CSVRecord record : records)
			{
				boolean useStringDBMapping = false;
				if (record.get("mode").equals("binding"))
				{
					Protein protein1 = new Protein(record.get("item_id_a"), ProteinIdentifierType.STRINGDB);
					Protein protein2 = new Protein(record.get("item_id_b"), ProteinIdentifierType.STRINGDB);
					Ppi ppi = new Ppi(protein1, protein2);
					ppis.add(ppi);
					writer.write(ppi.toString()+"\n");
				}
			}
		}
		catch (IOException e)
		{
			logger.error("File Error!", e);
		}
		return ppis;
	}

	private Set<Ppi> getPPIsWithExperiments(String stringDBProteinLinksFile)
	{
		Set<Ppi> ppis = new HashSet<>();
		String ppisWithExperimentsScoreFile = outputPath + this.stringDBSpeciesCode + "_PPIs_with_experiments.tsv";
		// First we have to filter for interactions in protein.links.full where experiment > 0
		try (FileReader reader = new FileReader(stringDBProteinLinksFile);
				CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT
										.withDelimiter(' ')
										.withFirstRecordAsHeader());
				FileWriter writer = new FileWriter(ppisWithExperimentsScoreFile);)
		{
			List<CSVRecord> records = parser.getRecords();
			logger.info("Reading StringDB \"links\" file; {} records will be parsed.", records.size());
			for (CSVRecord  record : records)
			{
				int experiments = Integer.parseInt(record.get("experiments"));
//				int dbScore = Integer.parseInt(record.get("database"));
				if (experiments > 0 /*|| dbScore > 0*/)
				{
					Protein protein1 = new Protein (record.get("protein1") , ProteinIdentifierType.STRINGDB);
					Protein protein2 = new Protein (record.get("protein2"), ProteinIdentifierType.STRINGDB);
					Ppi ppi = new Ppi(protein1, protein2);
					ppis.add(ppi);
					writer.write(ppi.toString()+"\n");
				}
			}
		}
		catch (IOException e)
		{
			logger.error("File Error!", e);
		}
		return ppis;
	}

	private Set<Ppi> getBindingPPIsWithExperiments(String stringDBActionsFile, String stringDBProteinLinksFile)
	{
		Set<Ppi> ppisWithExperiments = getPPIsWithExperiments(stringDBProteinLinksFile);
		logger.info("{} PPIs with experiments > 0", ppisWithExperiments.size());
		Set<Ppi> ppisBinding = getBindingPPIs(stringDBActionsFile);
		logger.info("{} PPIs with mode==binding", ppisBinding.size());
		Set<Ppi> ppisBindingAndExperiments = ppisBinding;
		ppisBindingAndExperiments.retainAll(ppisWithExperiments);
		logger.info("{} PPIs with experiments > 0 AND mode==binding", ppisBindingAndExperiments.size());
		return ppisBindingAndExperiments;
	}

	private String[] extractIdentifiersFromPantherLine(String[] parts, String species1)
	{
		String[] humanParts;
		String[] otherSpeciesParts;

		if (species1.equals(HUMAN))
		{
			humanParts = parts[0].split("\\|");
			otherSpeciesParts = parts[1].split("\\|");
		}
		else
		{
			humanParts = parts[1].split("\\|");
			otherSpeciesParts = parts[0].split("\\|");
		}
		// Now... get the identifier values for Human and the other species.
		String humanIdentifier = extractUniprotIdentifier(humanParts);
		String otherSpeciesIdentifier = extractUniprotIdentifier(otherSpeciesParts);

		return new String[]{ humanIdentifier, otherSpeciesIdentifier } ;
	}

	private Map<String, Set<String>> getOrthologMappedProteins(String pathToOrthologMappingFile, boolean allowBidirectionalMapping, String speciesName)
	{
		Map<String, Set<String>> otherSpeciesMappedToHuman = new HashMap<>();
		try(BufferedReader br = new BufferedReader(new FileReader(pathToOrthologMappingFile)))
		{
			String line = br.readLine();

			while (line != null)
			{
				String[] parts = line.split("\\t");
				// Only the first two parts are useful, and only if they contain HUMAN or YEAST (or whatever speciesName is).
				String species1 = (parts[0].split("\\|"))[0];
				String species2 = (parts[1].split("\\|"))[0];
				String geneFamily = parts[4];
				boolean lineIsUseful = !species1.equals(species2) && ((allowBidirectionalMapping && species1.equals(HUMAN) && species2.equals(speciesName))
																	|| (species1.equals(speciesName) && species2.equals(HUMAN)));
				if (lineIsUseful)
				{
					String[] extractedIdentifiers = extractIdentifiersFromPantherLine(parts, species1);
					String humanIdentifier = extractedIdentifiers[0];
					String otherSpeciesIdentifier = extractedIdentifiers[1];
					Set<String> identifiers;
					if (otherSpeciesMappedToHuman.containsKey(otherSpeciesIdentifier))
					{
						identifiers = otherSpeciesMappedToHuman.get(otherSpeciesIdentifier);
					}
					else
					{
						identifiers = new HashSet<>();
					}
					identifiers.add(humanIdentifier);
					otherSpeciesMappedToHuman.put(otherSpeciesIdentifier, identifiers);
				}
				line = br.readLine();
			}
		}
		catch (IOException e)
		{
			logger.error("File error!", e);
		}
		return otherSpeciesMappedToHuman;
	}

	private Map<String, Set<String>> getStringDBToUniProtMappings(String pathToMappings)
	{
		Map<String, Set<String>> mappings = new HashMap<>();
		// StringDB has a mappings file that shows StringDB -> UniProt
		try(BufferedReader br = new BufferedReader(new FileReader(pathToMappings)))
		{
			String line = br.readLine();

			while (line != null)
			{
				String[] parts = line.split("\\t");
				// 2nd and 3rd columns are the ones we're interested in.
				String stringDBIdentifier = parts[2];
				String uniProtIdentifier = parts[1].split("\\|")[0];

				Set<String> s = mappings.containsKey(stringDBIdentifier) ? mappings.get(stringDBIdentifier) : new HashSet<>();
				s.add(uniProtIdentifier);
				mappings.put(stringDBIdentifier, s);
				line = br.readLine();
			}
		}
		catch (IOException e)
		{
			logger.error("File Error!", e);
		}
		return mappings;
	}

	private void generateOtherSpeciesToHumanPPIs(String speciesName)
	{
		this.outputPath = "output/"+speciesName+"_results/";
		String stringDBProteinActionsFile = PATH_TO_DATA_FILES + this.stringDBSpeciesCode + ".protein.actions.v11.0.txt";
		String stringDBProteinLinksFile = PATH_TO_DATA_FILES + this.stringDBSpeciesCode + ".protein.links.full.v11.0.txt";

		Set<Ppi> ppisFromStringDB = this.getBindingPPIsWithExperiments(stringDBProteinActionsFile, stringDBProteinLinksFile);
		String pathToOrthologMappingFile = PATH_TO_DATA_FILES + "Orthologs_HCOP";
		Map<String, Set<String>> orthologProteins = this.getOrthologMappedProteins(pathToOrthologMappingFile, true, speciesName);
		logger.info("{} ortholog proteins", orthologProteins.size());
		Map<String, Set<String>> otherSpeciesStringDBToUniProt = getStringDBToUniProtMappings(PATH_TO_DATA_FILES + "yeast.uniprot_2_string.2018.tsv");
		logger.info("{} StringDB-to-UniProt identifiers", otherSpeciesStringDBToUniProt.size());
		String pathToOutputPPIFile = this.outputPath + speciesName + "_MAPPED_PPIS.tsv";
		int selfInteractions = 0;
		Set<Ppi> mappedPPIs = new HashSet<>();
		// If a PPI from StringDB (with experiments > 0 && mode==binding) has both proteins in the mapping, then it's OK! ...and should be written to output file.
		try(FileWriter writer = new FileWriter(pathToOutputPPIFile)) {
		for (Ppi ppi : ppisFromStringDB)
		{
			Protein p1 = ppi.getProtein1(), p2 = ppi.getProtein2();

			String p1AsUniProt = null, p2AsUniProt = null;
			if (p1.getIdentifierType().equals(ProteinIdentifierType.STRINGDB))
			{
				if (otherSpeciesStringDBToUniProt.containsKey(p1.getIdentifierValue()))
				{
					p1AsUniProt = otherSpeciesStringDBToUniProt.get(p1.getIdentifierValue()).stream().findFirst().get();
				}
//					else
//					{
//						logger.error("{} has a UniProt identifier but is not in mapping", p1);
//					}
			}
			if (p2.getIdentifierType().equals(ProteinIdentifierType.STRINGDB))
			{
				if (otherSpeciesStringDBToUniProt.containsKey(p2.getIdentifierValue()))
				{
					p2AsUniProt = otherSpeciesStringDBToUniProt.get(p2.getIdentifierValue()).stream().findFirst().get();
				}
//					else
//					{
//						logger.error("{} has a UniProt identifier but is not in mapping", p2);
//					}
			}

			if (p1AsUniProt != null && p2AsUniProt != null)
			{
				if (!p1AsUniProt.equals(p2AsUniProt))
				{
//						writer.write(p1AsUniProt + "\t" + p2AsUniProt + "\n");
//					Protein mappedP1 = new Protein(p1AsUniProt, ProteinIdentifierType.UNIPROT_ACCESSION);
//					Protein mappedP2 = new Protein(p2AsUniProt, ProteinIdentifierType.UNIPROT_ACCESSION);
//					Ppi mappedPpi = new Ppi(mappedP1, mappedP2);
//					mappedPPIs.add(mappedPpi);
					writer.write(p1AsUniProt + "\t" + p2AsUniProt + "\t" + "(" + p1AsUniProt + " was mapped from: " + ppi.getProtein1() + "; " + p2AsUniProt + " was mapped from: " + ppi.getProtein2() + ")" + System.lineSeparator() );
				}
				else
				{
					selfInteractions++;
				}
			}
		}
//		try(FileWriter writer = new FileWriter(pathToOutputPPIFile))
//		{
//			for (Ppi ppi : mappedPPIs.stream().sorted().collect(Collectors.toList()))
//			{
//				writer.write(ppi + "\n");
//			}
//		}
		}
		catch (IOException e)
		{
			logger.error("File error!", e);
		}
		logger.info("{} self-interactions were ommitted.", selfInteractions);
	}

	private static String extractUniprotIdentifier(String[] humanParts)
	{
		String identifier = null;
		String s;
		boolean done = false;
		int i = 0;
		while(!done && i < humanParts.length)
		{
			s = humanParts[i];
			if (s.startsWith("UniProt"))
			{
				identifier = s.substring(s.indexOf('=') + 1);
				done = true;
			}
			i++;
		}
		return identifier;
	}
}
