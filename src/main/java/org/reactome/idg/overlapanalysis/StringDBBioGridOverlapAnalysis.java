package org.reactome.idg.overlapanalysis;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactome.idg.model.Protein;
import org.reactome.idg.model.ProteinIdentifierType;
import org.reactome.idg.model.ProteinProteinInteraction;
import org.reactome.idg.util.StringDBUtil;

public class StringDBBioGridOverlapAnalysis
{
	private static Logger logger = LogManager.getLogger();
	private static String pathToData = "src/main/resources/data/";
	private static String pathToOutput = "output/overlaps/";

	public static void main(String... args)
	{
		logger.info("Calculating StringDB/Biogrid PPI overlap...");
		String stringDBProteinActionsFile = pathToData + "9606.protein.actions.v11.0.txt";
		String stringDBProteinLinksFile = pathToData + "9606.protein.links.full.v11.0.txt";
		String stringToEntrezGeneMapping = pathToData + "all_organisms.entrez_2_string.2018.tsv";
		String stringToUniProtMapping = pathToData + "all_organisms.uniprot_2_string.2018.tsv";
		String bioGridPPIFile = pathToData + "BIOGRID-ORGANISM/BIOGRID-ORGANISM-Homo_sapiens-3.5.181.tab2.txt";
		String humanOranismTaxonID = "9606";
		// Step 1: get PPIs from String
		// Step 2: get PPIs from BioGrid
		// Step 3: map BioGrid PPIs to StringDB
		// Step 4: calculate overlap
		Set<ProteinProteinInteraction> interactionsWithExperiments = StringDBUtil.getPPIsWithExperiments(0, stringDBProteinLinksFile);
		Set<ProteinProteinInteraction> bindingInteractionsWithExperiments = StringDBUtil.getBindingPPIs(stringDBProteinActionsFile, interactionsWithExperiments);
		logger.info("Number of PPIs from StringDB: {}", bindingInteractionsWithExperiments.size());
		// Get BioGrid data
		Set<ProteinProteinInteraction> ppisFromBioGrid = new HashSet<>();
		ppisFromBioGrid = getBioGridPPIs(bioGridPPIFile, humanOranismTaxonID);
		logger.info("Number of PPIs from BioGrid: {}", ppisFromBioGrid.size());
		// Map the BioGrid data to StringDB using StringDB's EntrezGene<->StringDB mapping file.
		final Map<String, Set<String>> entrezGeneToString = mapBioGridToStringDB(stringToEntrezGeneMapping, humanOranismTaxonID);
		logger.info("Number of EntrezGene -> StringDB identifier mappings: {}", entrezGeneToString.size());

		Set<ProteinProteinInteraction> mappedPPIsFromBioGrid = new HashSet<>();
		mappedPPIsFromBioGrid = mapBioGridPPIs(ppisFromBioGrid, entrezGeneToString);

		logger.info("Number of MAPPED (to StringDB) BioGrid PPIs: {}", mappedPPIsFromBioGrid.size());

		// Now calculate overlap.
		Set<ProteinProteinInteraction> overlap = new HashSet<>(bindingInteractionsWithExperiments);
		overlap.retainAll(mappedPPIsFromBioGrid);
		logger.info("Size of StringDB/BioGrid PPI Overlap: {}", overlap.size());

		// Before writing to output, load the UniProt mapping. We'll use that to map to UniProt when writing the data to file
		Map<String, Set<String>> stringToUniProt = new HashMap<>();
		try(FileReader reader = new FileReader(stringToUniProtMapping))
		{
			CSVFormat format = CSVFormat.DEFAULT.withDelimiter('\t');
			try(CSVParser parser = new CSVParser(reader, format);)
			{
				parser.forEach( record ->
				{
					if (record.get(0).equals(humanOranismTaxonID))
					{
						String uniprot = record.get(1);
						String stringDBID = record.get(2);
						String[] uniprotParts = uniprot.split("\\|");
						String uniprotAccession = uniprotParts[0];
						Set<String> uniprots = stringToUniProt.computeIfAbsent(stringDBID, x -> new HashSet<>());
						uniprots.add(uniprotAccession);
						stringToUniProt.put(stringDBID, uniprots);
					}
				});
			}
		}
		catch (IOException e)
		{
			logger.error("File error!", e);
		}
		logger.info("{} StringDB-to-UniProt mappings loaded", stringToUniProt.size());
		try(FileWriter overlapWriter = new FileWriter(pathToOutput + "StringDB-BioGrid-PPIoverlap.tsv");
			FileWriter stringDBRemainderWriter = new FileWriter(pathToOutput + "StringDB-only-PPIs.tsv");
			FileWriter bioGridRemainderWriter = new FileWriter(pathToOutput + "BioGrid-only-PPIs.tsv");
			FileWriter stringToUniProtMappingFailure = new FileWriter(pathToOutput + "stringToUniprotMappingFailure.txt"))
		{
			for (ProteinProteinInteraction ppi : overlap.stream().sorted().collect(Collectors.toList()))
			{
				processPPI(stringToUniProt, overlapWriter, stringToUniProtMappingFailure, ppi);
			}
			// Now write the StringDB PPIs that are not in overlap
			for (ProteinProteinInteraction ppi : bindingInteractionsWithExperiments.stream().sorted().collect(Collectors.toList()))
			{
				if (!overlap.contains(ppi))
				{
					processPPI(stringToUniProt, stringDBRemainderWriter, stringToUniProtMappingFailure, ppi);
				}
			}
			// Now write the BioGrid PPIs that are not in overlap
			for (ProteinProteinInteraction ppi : mappedPPIsFromBioGrid.stream().sorted().collect(Collectors.toList()))
			{
				if (!overlap.contains(ppi))
				{
					processPPI(stringToUniProt, bioGridRemainderWriter, stringToUniProtMappingFailure, ppi);
				}

			}
		}
		catch (IOException e)
		{
			logger.error("File error!", e);
		}
	}

	private static Set<ProteinProteinInteraction> mapBioGridPPIs(Set<ProteinProteinInteraction> ppisFromBioGrid, final Map<String, Set<String>> entrezGeneToString)
	{
		Set<ProteinProteinInteraction> mappedPPIsFromBioGrid = new HashSet<>();
		AtomicInteger selfInteractionCount = new AtomicInteger(0);
		Set<String> failedMappings = new HashSet<>();
		// Now need to update the BioGrid PPIs
		ppisFromBioGrid.forEach(ppi -> {
			Set<String> mapped1 = entrezGeneToString.get(ppi.getProtein1().getIdentifierValue());
			Set<String> mapped2 = entrezGeneToString.get(ppi.getProtein2().getIdentifierValue());

			if (mapped1 != null && mapped2 != null)
			{
				for (String mappedProtein1 : mapped1)
				{
					for (String mappedProtein2 : mapped2)
					{
						if (mappedProtein1 != null && mappedProtein2 != null)
						{
							if (!mappedProtein1.equals(mappedProtein2))
							{
								mappedPPIsFromBioGrid.add(new ProteinProteinInteraction(new Protein(mappedProtein1, ProteinIdentifierType.STRINGDB), new Protein(mappedProtein2, ProteinIdentifierType.STRINGDB)));
							}
							else
							{
								// self-interaction
								selfInteractionCount.incrementAndGet();
							}
						}
					}
				}
			}
			else
			{
				//else mapping fail.
				if (mapped1 == null)
				{
					failedMappings.add(ppi.getProtein1().getIdentifierValue());
				}
				if (mapped2 == null)
				{
					failedMappings.add(ppi.getProtein2().getIdentifierValue());
				}
			}
		});
		try(FileWriter failedMappingWriter = new FileWriter(pathToOutput + "failedMappingsFromBioGrid.txt"))
		{
			for (String entrezGeneID : failedMappings)
			{
				failedMappingWriter.write(entrezGeneID + "\n");
			}
		}
		catch (IOException e)
		{
			logger.error("File error!", e);
		}

		logger.info("Number of self-interactions (caused by mapping to UniProt) from BioGrid ignored: {}", selfInteractionCount);
		return mappedPPIsFromBioGrid;
	}

	private static Map<String, Set<String>> mapBioGridToStringDB(String stringToEntrezGeneMapping, String humanOranismTaxonID)
	{
		Map<String, Set<String>> entrezGeneToString = new HashMap<>();
		try(FileReader reader = new FileReader(stringToEntrezGeneMapping))
		{
			CSVFormat format = CSVFormat.DEFAULT.withCommentMarker('#').withDelimiter('\t');
			try(CSVParser parser = new CSVParser(reader, format);)
			{
				parser.forEach( record -> {
					if (record.get(0).equals(humanOranismTaxonID))
					{
						String entrezGeneIDList = record.get(1);
						String[] entrezGeneIDs = entrezGeneIDList.split("\\|");
						for (String entrezGeneID : entrezGeneIDs)
						{
							String stringDBID = record.get(2);
							if (entrezGeneID != null && stringDBID != null)
							{
								Set<String> stringDBIDs = entrezGeneToString.computeIfAbsent(entrezGeneID, x -> new HashSet<>());
								stringDBIDs.add(stringDBID);
								entrezGeneToString.put(entrezGeneID, stringDBIDs);
							}
						}
					}
				});
			}
		}
		catch (IOException e)
		{
			logger.error("File error!", e);
		}
		return entrezGeneToString;
	}

	private static Set<ProteinProteinInteraction> getBioGridPPIs(String bioGridPPIFile, String oranismTaxonID)
	{
		int selfInteractions = 0;
		Set<ProteinProteinInteraction> ppisFromBioGrid = new HashSet<>();
		try(FileReader reader = new FileReader(bioGridPPIFile))
		{
			CSVFormat format = CSVFormat.DEFAULT
					.withDelimiter('\t')
					.withFirstRecordAsHeader();
			try(CSVParser parser = new CSVParser(reader, format);)
			{
				List<CSVRecord> records = parser.getRecords();
//				int totalNumRecords = records.size();
//				int unMappedCount = 0;
//				int recordsWithNonHumanInteractor = 0;
				for (CSVRecord record : records)
				{
					String organismA = record.get("Organism Interactor A");
					String organismB = record.get("Organism Interactor B");
//					if (!organismA.trim().equals(humanOranismTaxonID) || !organismB.trim().equals(humanOranismTaxonID))
//					{
//						recordsWithNonHumanInteractor++;
//					}
					if (organismA != null && organismB != null
						&& organismA.trim().equals(oranismTaxonID)
						&& organismB.trim().equals(oranismTaxonID))
					{
//						String score = record.get("Score");
//						if (score != null && !score.equals("-") && Double.parseDouble(score) > 0.75d)
						{
							String entrezGeneA = record.get("Entrez Gene Interactor A");
							String entrezGeneB = record.get("Entrez Gene Interactor B");
							// avoid self-interactions
							if (!entrezGeneA.equals(entrezGeneB))
							{
								ppisFromBioGrid.add(new ProteinProteinInteraction(new Protein(entrezGeneA, ProteinIdentifierType.ENTREZ_GENE), new Protein(entrezGeneB, ProteinIdentifierType.ENTREZ_GENE)));
							}
							else
							{
								selfInteractions++;
							}
						}

					}
				}
			}
		}
		catch (IOException e)
		{
			logger.error("File error!", e);
		}
		logger.info("{} BioGrid self-interactions skipped.", selfInteractions);
		return ppisFromBioGrid;
	}

	private static void processPPI(Map<String, Set<String>> stringToUniProtMapping, FileWriter mainWriter, FileWriter mappingFailureWriter, ProteinProteinInteraction ppi) throws IOException
	{
		final String p1 = ppi.getProtein1().getIdentifierValue();
		final String p2 = ppi.getProtein2().getIdentifierValue();
		final Set<String> mappedP1 = stringToUniProtMapping.get(p1);
		final Set<String> mappedP2 = stringToUniProtMapping.get(p2);
		if (stringToUniProtMapping.get(p1) != null && stringToUniProtMapping.get(p2) != null)
		{
			for (String uniprot1 : mappedP1)
			{
				for (String uniprot2 : mappedP2)
				{
					mainWriter.write(uniprot1 + "\t" + uniprot2 +"\n");
				}
			}
		}
		else
		{
			if (mappedP1 == null)
			{
				mappingFailureWriter.write(p1 + "\n");
			}
			if (mappedP2 == null)
			{
				mappingFailureWriter.write(p2 + "\n");
			}
		}
	}

}
