/*
 * Copyright (c) 2016-2017 Projekt Substratum
 * This file is part of Substratum.
 *
 * Substratum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Substratum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Substratum.  If not, see <http://www.gnu.org/licenses/>.
 */

package projekt.substratum.util.readers;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public enum ReadCloudWallpaperFile {
    ;

    public static Map main(final String[] argv) {

        final Map<String, String> map = new TreeMap<>();
        final Map<String, String> emptyMap = new TreeMap<>();

        try {
            final File fXmlFile = new File(argv[0]);

            final DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            final DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            final Document doc = dBuilder.parse(fXmlFile);
            doc.getDocumentElement().normalize();
            final NodeList nList = doc.getElementsByTagName("wallpaper");

            for (int temp = 0; temp < nList.getLength(); temp++) {
                final Node nNode = nList.item(temp);
                if (nNode.getNodeType() == Node.ELEMENT_NODE) {
                    final Element eElement = (Element) nNode;

                    // Replace all spaces with a tilde first, as tilde "~" is lower priority than
                    // "-", we have to put this first.
                    final String addon_download_name = eElement.getAttribute("id")
                            .replaceAll("\\s+", "~");
                    final String addon_download_link = eElement.getElementsByTagName("link").item(0).
                            getTextContent();
                    final String addon_preview_link = eElement.getElementsByTagName("preview").item(0).
                            getTextContent();

                    final String[] finalArray = {addon_download_name, addon_download_link,
                            addon_preview_link};

                    map.put(finalArray[0], finalArray[1]);
                    map.put(finalArray[0] + "-preview", finalArray[2]);
                }
            }
            return map;
        } catch (final Exception e) {
            return emptyMap;
        }
    }
}