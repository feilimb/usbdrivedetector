/*
 * Copyright 2014 samuelcampos.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.samuelcampos.usbdrivedetector.detectors;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.filechooser.FileSystemView;

import com.github.tuupertunut.powershelllibjava.PowerShell;
import com.github.tuupertunut.powershelllibjava.PowerShellExecutionException;

import lombok.extern.slf4j.Slf4j;
import net.samuelcampos.usbdrivedetector.USBStorageDevice;
import net.samuelcampos.usbdrivedetector.process.CommandExecutor;

/**
 *
 * @author samuelcampos
 */
@Slf4j
public class WindowsStorageDeviceDetector extends AbstractStorageDeviceDetector {

    private static final String WMIC_PATH = System.getenv("WINDIR") + "\\System32\\wbem\\wmic.exe";

    /**
     * wmic logicaldisk where drivetype=2 get description,deviceid,volumename
     */
    private static final String CMD_WMI_ARGS = "logicaldisk where drivetype=2 get DeviceID,VolumeSerialNumber";
    private static final String CMD_WMI_USB = WMIC_PATH + " " + CMD_WMI_ARGS;
    private static final String GET_VOLUME_FILESYSTEMLABEL_PROPERTY_NAME = "FileSystemLabel : ";
    private static final String GET_VOLUME_FILESYSTEMLABEL_CMD_TEMPLATE = "Get-Volume -DriveLetter %s | Format-List -Property FileSystemLabel";

    protected WindowsStorageDeviceDetector() {
        super();
    }

    @Override
    public List<USBStorageDevice> getStorageDevices() {
        final ArrayList<USBStorageDevice> listDevices = new ArrayList<>();

        try (CommandExecutor commandExecutor = new CommandExecutor(CMD_WMI_USB)) {
            commandExecutor.processOutput(outputLine -> {

        	final String[] parts = outputLine.split(" ");

                if(parts.length > 1 && !parts[0].isEmpty() && !parts[0].equals("DeviceID") && !parts[0].equals(parts[parts.length - 1])) {
                	final String rootPath = parts[0] + File.separatorChar;
                    final String uuid = parts[parts.length - 1];

                    getUSBDevice(rootPath, getDeviceName(rootPath), rootPath, uuid)
                            .ifPresent(listDevices::add);
                }
            });

        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        return listDevices;
    }

    private Collection<String> getValuesByProperty(final String psOutput, final String propertyTag)
    {
    	return Arrays.asList(psOutput.split("\\r\\n")).stream()
        		.filter(l -> l.startsWith(propertyTag))
        		.map(l -> l.substring(propertyTag.length()))
        		.collect(Collectors.toList());
    }
    
    private String getDeviceName(final String rootPath) {
        final File f = new File(rootPath);
        
    	if (rootPath.contains(":"))
    	{
    		// first approach: let's use powershell to derive the volume label
	        try {
	        	PowerShell psSession = PowerShell.open();
	        	final String driveLetter = rootPath.substring(0, rootPath.indexOf(":"));
	        	
	    		String cmd = String.format(GET_VOLUME_FILESYSTEMLABEL_CMD_TEMPLATE, driveLetter);
	    		String fileSystemLabelOutput = psSession.executeCommands(cmd);
	    		Collection<String> fileSystemLabel = getValuesByProperty(fileSystemLabelOutput, GET_VOLUME_FILESYSTEMLABEL_PROPERTY_NAME);
	    		if (!fileSystemLabel.isEmpty() && fileSystemLabel.size()==1)
	    		{
	    			final String label = fileSystemLabel.iterator().next();
	    			// note: if there is no volume label, we fall-back on 
	    			// [FileSystemView.getSystemDisplayName(File)] approach => this can 
	    			// be useful as it will derive the user-friendly pseudo label 
	    			// 'USB Drive' (with localisation) prefix as is shown in eg. Windows Explorer
	    			if (!label.isEmpty())
	    			{
	    				return label;
	    			}
	    		}
			} catch (IOException|PowerShellExecutionException e) {
				// Such an exception may be encountered if eg. a USB device is 
				// ejected while the powershell cmd is being executed. In such 
				// a case, let's just fall-back on pre-existing 
				// [FileSystemView.getSystemDisplayName(File)] approach.
	        }
    	}

        // fall-back approach: let's use [FileSystemView.getSystemDisplayName(File)] to determine
    	// volume label (including pseudo label when no actual label exists)
        final FileSystemView v = FileSystemView.getFileSystemView();
        String name = v.getSystemDisplayName(f);

        if (name != null) {
            int idx = name.lastIndexOf('(');
            if (idx != -1) {
                name = name.substring(0, idx);
            }

            name = name.trim();
            if (name.isEmpty()) {
                name = null;
            }
        }
        return name;
    }
}
