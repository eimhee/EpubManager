package com.github.epubreader.manager.epub;

import com.github.epubreader.manager.Constants;
import com.github.epubreader.manager.domain.*;
import com.github.epubreader.manager.exception.ReadingException;
import com.github.epubreader.manager.service.MediatypeService;
import com.github.epubreader.manager.util.PathUtil;
import com.github.epubreader.manager.util.ResourceUtil;
import com.github.epubreader.manager.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;

/**
 * Reads the opf package document as defined by namespace http://www.idpf.org/2007/opf
 *  
 * @author paul
 *
 */
public class PackageDocumentReader extends PackageDocumentBase {
	
	private static final Logger log = LoggerFactory.getLogger(PackageDocumentReader.class);
	private static final String[] POSSIBLE_NCX_ITEM_IDS = new String[] {"toc", "ncx", "ncxtoc"};
	
	
	public static void read(Resource packageResource, EpubReader epubReader, Book book, Resources resources) throws UnsupportedEncodingException, SAXException, IOException, ParserConfigurationException {
		Document packageDocument = ResourceUtil.getAsDocument(packageResource);
		if (packageDocument == null) {
			throw new ReadingException("packageDocument not found");
		}
		String packageHref = packageResource.getHref();
		//resources = fixHrefs(packageHref, resources);
		readGuide(packageDocument, epubReader, book, resources, packageHref);
		
		// Books sometimes use non-identifier ids. We map these here to legal ones
		Map<String, String> idMapping = new HashMap<String, String>();
		
		resources = readManifest(packageDocument, packageHref, epubReader, resources, idMapping);
		book.setResources(resources);
		readCover(packageDocument, book, packageHref);
		book.setMetadata(PackageDocumentMetadataReader.readMetadata(packageDocument));
		book.setSpine(readSpine(packageDocument, book.getResources(), idMapping));
		
		// if we did not find a cover page then we make the first page of the book the cover page
		if (book.getCoverPage() == null && book.getSpine().size() > 0) {
			book.setCoverPage(book.getSpine().getResource(0));
		}
	}
	
//	private static Resource readCoverImage(Element metadataElement, Resources resources) {
//		String coverResourceId = DOMUtil.getFindAttributeValue(metadataElement.getOwnerDocument(), NAMESPACE_OPF, OPFTags.meta, OPFAttributes.name, OPFValues.meta_cover, OPFAttributes.content);
//		if (StringUtil.isBlank(coverResourceId)) {
//			return null;
//		}
//		Resource coverResource = resources.getByIdOrHref(coverResourceId);
//		return coverResource;
//	}
	

	
	/**
	 * Reads the manifest containing the resource ids, hrefs and mediatypes.
	 *  
	 * @param packageDocument
	 * @param packageHref
	 * @param epubReader
	 * @return a Map with resources, with their id's as key.
	 */
	private static Resources readManifest(Document packageDocument, String packageHref,
			EpubReader epubReader, Resources resources, Map<String, String> idMapping) {
		Element manifestElement = DOMUtil.getFirstElementByTagNameNS(packageDocument.getDocumentElement(), NAMESPACE_OPF, OPFTags.manifest);
		Resources result = new Resources();
		if(manifestElement == null) {
			log.error("Package document does not contain element " + OPFTags.manifest);
			return result;
		}
		NodeList itemElements = manifestElement.getElementsByTagNameNS(NAMESPACE_OPF, OPFTags.item);
		for(int i = 0; i < itemElements.getLength(); i++) {
			Element itemElement = (Element) itemElements.item(i);
			String id = DOMUtil.getAttribute(itemElement, NAMESPACE_OPF, OPFAttributes.id);
			String href = DOMUtil.getAttribute(itemElement, NAMESPACE_OPF, OPFAttributes.href);
			try {
				href = URLDecoder.decode(href, Constants.CHARACTER_ENCODING);
			} catch (UnsupportedEncodingException e) {
				log.error(e.getMessage());
			}
			String mediaTypeName = DOMUtil.getAttribute(itemElement, NAMESPACE_OPF, OPFAttributes.media_type);
			String reference = PathUtil.resolveRelativeReference(packageHref, href, null);

			Resource resource = resources.remove(reference);
			if(resource == null) {
				log.error("resource with href '" + href + "' not found");
				continue;
			}
			resource.setId(id);
			MediaType mediaType = MediatypeService.getMediaTypeByName(mediaTypeName);
			if(mediaType != null) {
				resource.setMediaType(mediaType);
			}
			// add properties
			String properties = DOMUtil.getAttribute(itemElement, NAMESPACE_OPF, OPFAttributes.properties);
			if (StringUtil.isNotBlank(properties)) {
				resource.setProperties(properties.trim());
			}
			result.add(resource);
			idMapping.put(id, resource.getId());
		}
		return result;
	}	

	
	
	
	/**
	 * Reads the book's guide.
	 * Here some more attempts are made at finding the cover page.
	 * 
	 * @param packageDocument
	 * @param epubReader
	 * @param book
	 * @param resources
	 */
	private static void readGuide(Document packageDocument,
								  EpubReader epubReader, Book book, Resources resources, String packageHref) {
		Element guideElement = DOMUtil.getFirstElementByTagNameNS(packageDocument.getDocumentElement(), NAMESPACE_OPF, OPFTags.guide);
		if(guideElement == null) {
			return;
		}
		Guide guide = book.getGuide();
		NodeList guideReferences = guideElement.getElementsByTagNameNS(NAMESPACE_OPF, OPFTags.reference);
		for (int i = 0; i < guideReferences.getLength(); i++) {
			Element referenceElement = (Element) guideReferences.item(i);
			String resourceHref = DOMUtil.getAttribute(referenceElement, NAMESPACE_OPF, OPFAttributes.href);
			if (StringUtil.isBlank(resourceHref)) {
				continue;
			}
			String referenceHref = PathUtil.resolveRelativeReference(packageHref, StringUtil.substringBefore(resourceHref, Constants.FRAGMENT_SEPARATOR_CHAR), null);

			Resource resource = resources.getByHref(StringUtil.substringBefore(referenceHref, Constants.FRAGMENT_SEPARATOR_CHAR));
			if (resource == null) {
				log.error("Guide is referencing resource with href " + resourceHref + " which could not be found");
				continue;
			}
			String type = DOMUtil.getAttribute(referenceElement, NAMESPACE_OPF, OPFAttributes.type);
			if (StringUtil.isBlank(type)) {
				log.error("Guide is referencing resource with href " + resourceHref + " which is missing the 'type' attribute");
				continue;
			}
			String title = DOMUtil.getAttribute(referenceElement, NAMESPACE_OPF, OPFAttributes.title);
			if (GuideReference.COVER.equalsIgnoreCase(type)) {
				continue; // cover is handled elsewhere
			}
			GuideReference reference = new GuideReference(resource, type, title, StringUtil.substringAfter(resourceHref, Constants.FRAGMENT_SEPARATOR_CHAR));
			guide.addReference(reference);
		}
	}


	/**
	 * Strips off the package prefixes up to the href of the packageHref.
	 * 
	 * Example:
	 * If the packageHref is "OEBPS/content.opf" then a resource href like "OEBPS/foo/bar.html" will be turned into "foo/bar.html"
	 * 
	 * @param packageHref
	 * @param resourcesByHref
	 * @return The stripped package href
	 */
	static Resources fixHrefs(String packageHref,
			Resources resourcesByHref) {
		int lastSlashPos = packageHref.lastIndexOf('/');
		if(lastSlashPos < 0) {
			return resourcesByHref;
		}
		Resources result = new Resources();
		for(Resource resource: resourcesByHref.getAll()) {
			if(StringUtil.isNotBlank(resource.getHref())
					&& resource.getHref().length() > lastSlashPos) {
				resource.setHref(resource.getHref().substring(lastSlashPos + 1));
			}
			result.add(resource);
		}
		return result;
	}

	/**
	 * Reads the document's spine, containing all sections in reading order.
	 * 
	 * @param packageDocument
	 * @param epubReader
	 * @param book
	 * @param resourcesById
	 * @return the document's spine, containing all sections in reading order.
	 */
	private static Spine readSpine(Document packageDocument, Resources resources, Map<String, String> idMapping) {
		
		Element spineElement = DOMUtil.getFirstElementByTagNameNS(packageDocument.getDocumentElement(), NAMESPACE_OPF, OPFTags.spine);
		if (spineElement == null) {
			log.error("Element " + OPFTags.spine + " not found in package document, generating one automatically");
			return generateSpineFromResources(resources);
		}
		Spine result = new Spine();
		String tocResourceId = DOMUtil.getAttribute(spineElement, NAMESPACE_OPF, OPFAttributes.toc);
		result.setTocResource(findTableOfContentsResource(tocResourceId, resources));
		NodeList spineNodes = packageDocument.getElementsByTagNameNS(NAMESPACE_OPF, OPFTags.itemref);
		List<SpineReference> spineReferences = new ArrayList<SpineReference>(spineNodes.getLength());
		for(int i = 0; i < spineNodes.getLength(); i++) {
			Element spineItem = (Element) spineNodes.item(i);
			String itemref = DOMUtil.getAttribute(spineItem, NAMESPACE_OPF, OPFAttributes.idref);
			if(StringUtil.isBlank(itemref)) {
				log.error("itemref with missing or empty idref"); // XXX
				continue;
			}
			String id = idMapping.get(itemref);
			if (id == null) {
				id = itemref;
			}
			Resource resource = resources.getByIdOrHref(id);
			if(resource == null) {
				log.error("resource with id \'" + id + "\' not found");
				continue;
			}
			
			SpineReference spineReference = new SpineReference(resource);
			if (OPFValues.no.equalsIgnoreCase(DOMUtil.getAttribute(spineItem, NAMESPACE_OPF, OPFAttributes.linear))) {
				spineReference.setLinear(false);
			}
			spineReferences.add(spineReference);
		}
		result.setSpineReferences(spineReferences);
		return result;
	}

	/**
	 * Creates a spine out of all resources in the resources.
	 * The generated spine consists of all XHTML pages in order of their href.
	 * 
	 * @param resources
	 * @return a spine created out of all resources in the resources.
	 */
	private static Spine generateSpineFromResources(Resources resources) {
		Spine result = new Spine();
		List<String> resourceHrefs = new ArrayList<String>();
		resourceHrefs.addAll(resources.getAllHrefs());
		Collections.sort(resourceHrefs, String.CASE_INSENSITIVE_ORDER);
		for (String resourceHref: resourceHrefs) {
			Resource resource = resources.getByHref(resourceHref);
			if (resource.getMediaType() == MediatypeService.NCX) {
				result.setTocResource(resource);
			} else if (resource.getMediaType() == MediatypeService.XHTML) {
				result.addSpineReference(new SpineReference(resource));
			}
		}
		return result;
	}

	
	/**
	 * The spine tag should contain a 'toc' attribute with as value the resource id of the table of contents resource.
	 * 
	 * Here we try several ways of finding this table of contents resource.
	 * We try the given attribute value, some often-used ones and finally look through all resources for the first resource with the table of contents mimetype.
	 *
	 * @return the Resource containing the table of contents
	 */
	static Resource findTableOfContentsResource(String tocResourceId, Resources resources) {
		Resource tocResource = null;
		if (StringUtil.isNotBlank(tocResourceId)) {
			tocResource = resources.getById(tocResourceId);
		}
		
		if (tocResource != null) {
			return tocResource;
		}
		
		// get the first resource with the NCX mediatype
		tocResource = resources.findFirstResourceByProperties("nav");
		// get the toc by properties nav
		if (tocResource != null) {
			return tocResource;
		}

		//Element manifestElement = DOMUtil.getFirstElementByTagNameNS(packageDocument.getDocumentElement(), NAMESPACE_OPF, OPFTags.manifest);
		//manifestElement.getE
		if (tocResource == null) {
			for (int i = 0; i < POSSIBLE_NCX_ITEM_IDS.length; i++) {
				tocResource = resources.getByIdOrHref(POSSIBLE_NCX_ITEM_IDS[i]);
				if (tocResource != null) {
					break;
				}
				tocResource = resources.getByIdOrHref(POSSIBLE_NCX_ITEM_IDS[i].toUpperCase());
				if (tocResource != null) {
					break;
				}
			}
		}

		if (tocResource == null) {
			log.error("Could not find table of contents resource. Tried resource with id '" + tocResourceId + "', " + Constants.DEFAULT_TOC_ID + ", " + Constants.DEFAULT_TOC_ID.toUpperCase() + " and any NCX resource.");
		}
		return tocResource;
	}


	/**
	 * Find all resources that have something to do with the coverpage and the cover image.
	 * Search the meta tags and the guide references
	 * 
	 * @param packageDocument
	 * @return all resources that have something to do with the coverpage and the cover image.
	 */
	// package
	static Set<String> findCoverHrefs(Document packageDocument) {
		
		Set<String> result = new HashSet<String>();
		
		// try and find a meta tag with name = 'cover' and a non-blank id
		String coverResourceId = DOMUtil.getFindAttributeValue(packageDocument, NAMESPACE_OPF,
											OPFTags.meta, OPFAttributes.name, OPFValues.meta_cover,
											OPFAttributes.content);

		if (StringUtil.isNotBlank(coverResourceId)) {
			String coverHref = DOMUtil.getFindAttributeValue(packageDocument, NAMESPACE_OPF,
					OPFTags.item, OPFAttributes.id, coverResourceId,
					OPFAttributes.href);
			if (StringUtil.isNotBlank(coverHref)) {
				result.add(coverHref);
			} else {
				result.add(coverResourceId); // maybe there was a cover href put in the cover id attribute
			}
		}
		// try and find a reference tag with type is 'cover' and reference is not blank
		String coverHref = DOMUtil.getFindAttributeValue(packageDocument, NAMESPACE_OPF,
											OPFTags.reference, OPFAttributes.type, OPFValues.reference_cover,
											OPFAttributes.href);
		if (StringUtil.isNotBlank(coverHref)) {
			result.add(coverHref);
		}

		// for epub 3.0
		String coverResourceId3 = DOMUtil.getFindAttributeValue(packageDocument, NAMESPACE_OPF,
				OPFTags.item, OPFAttributes.properties, OPFValues.cover_image,
				OPFAttributes.href);

		if (StringUtil.isNotBlank(coverResourceId3)) {
			result.add(coverResourceId3);
		}
		return result;
	}

	/**
	 * Finds the cover resource in the packageDocument and adds it to the book if found.
	 * Keeps the cover resource in the resources map
	 * @param packageDocument
	 * @param book
	 * @param resources
	 */
	private static void readCover(Document packageDocument, Book book, String packageHref) {
		
		Collection<String> coverHrefs = findCoverHrefs(packageDocument);
		for (String coverHref: coverHrefs) {
			String referenceHref = PathUtil.resolveRelativeReference(packageHref, coverHref, null);

			Resource resource = book.getResources().getByHref(referenceHref);
			if (resource == null) {
				log.error("Cover resource " + coverHref + " not found");
				continue;
			}
			if (resource.getMediaType() == MediatypeService.XHTML) {
				book.setCoverPage(resource);
			} else if (MediatypeService.isBitmapImage(resource.getMediaType())) {
				book.setCoverImage(resource);
			}
		}
	}
	

}
