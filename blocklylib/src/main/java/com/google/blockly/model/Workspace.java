/*
 * Copyright  2015 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.blockly.model;

import android.content.Context;
import android.content.res.AssetManager;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.FragmentManager;
import android.support.v4.widget.DrawerLayout;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.google.blockly.ToolboxFragment;
import com.google.blockly.TrashFragment;
import com.google.blockly.WorkspaceFragment;
import com.google.blockly.control.BlockCopyBuffer;
import com.google.blockly.control.ConnectionManager;
import com.google.blockly.control.Dragger;
import com.google.blockly.control.ProcedureManager;
import com.google.blockly.control.WorkspaceStats;
import com.google.blockly.ui.BlockGroup;
import com.google.blockly.ui.BlockView;
import com.google.blockly.ui.ViewPoint;
import com.google.blockly.ui.WorkspaceHelper;
import com.google.blockly.ui.WorkspaceView;
import com.google.blockly.utils.BlocklyXmlHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Controller for the workspace.  Keeps track of all the global state used in the workspace and
 * manages interaction between the different fragments.
 */
public class Workspace {
    private static final String TAG = "Workspace";
    private static final boolean DEBUG = true;

    private final ArrayList<Block> mRootBlocks = new ArrayList<>();
    private final ProcedureManager mProcedureManager = new ProcedureManager();
    private final NameManager mVariableNameManager = new NameManager.VariableNameManager();
    private final ConnectionManager mConnectionManager = new ConnectionManager();
    private final WorkspaceStats mStats = new WorkspaceStats(mVariableNameManager, mProcedureManager,
            mConnectionManager);
    private final ToolboxCategory mDeletedBlocks = new ToolboxCategory();
    private final BlockCopyBuffer mCopyBuffer = new BlockCopyBuffer();
    private final ViewPoint mTempViewPoint = new ViewPoint();
    private final Context mContext;
    private final Dragger mDragger;
    private ToolboxCategory mToolboxCategory;
    private BlockFactory mBlockFactory;

    // The FragmentManager is used to show/hide fragments like the trash and toolbox.
    private FragmentManager mFragmentManager;
    // The Workspace is the controller for the toolbox and trash as well as for the contents of
    // the main workspace.
    private ToolboxFragment mToolboxFragment;
    private DrawerLayout mToolboxDrawer;
    private boolean mCanCloseToolbox;
    // The trash can is currently just another instance of a toolbox: it holds blocks that can be
    // dragged into the workspace.
    private TrashFragment mTrashFragment;
    private boolean mCanCloseTrash;
    // The workspace fragment contains the main editor for users block code.
    private WorkspaceFragment mWorkspaceFragment;

    private WorkspaceHelper mWorkspaceHelper;
    private WorkspaceHelper.BlockTouchHandler mTouchHandler;
    private WorkspaceView mWorkspaceView;

    /**
     * Create a workspace controller.
     *
     * @param context The activity context.
     * @param blockFactory The factory to use for building new blocks.
     * @param style The resource id to load style configuration from. The style must inherit from
     * {@link com.google.blockly.R.style#BlocklyTheme}
     */
    private Workspace(Context context, BlockFactory blockFactory, int style) {
        if (context == null) {
            throw new IllegalArgumentException("context may not be null.");
        }
        if (blockFactory == null) {
            throw new IllegalArgumentException("blockFactory may not be null.");
        }
        mContext = context;
        mBlockFactory = blockFactory;
        mWorkspaceHelper = new WorkspaceHelper(mContext, null, style);
        mDragger = new Dragger(mWorkspaceHelper, mConnectionManager, mRootBlocks);
    }

    /**
     * Adds a new block to the workspace as a root block.
     *
     * @param block The block to add to the root of the workspace.
     */
    public void addRootBlock(Block block) {
        if (block == null) {
            throw new IllegalArgumentException("Cannot add a null block as a root block");
        }
        if (block.getPreviousBlock() != null) {
            throw new IllegalArgumentException("Root blocks may not have a previous block");
        }
        if (mRootBlocks.contains(block)) {
            throw new IllegalArgumentException("Block is already a root block.");
        }
        mRootBlocks.add(block);
        mStats.collectStats(block, true);
    }

    /**
     * Remove a block from the workspace and put it in the trash.
     *
     * @param block The block block to remove, possibly with descendants attached.
     *
     * @return True if the block was removed, false otherwise.
     */
    public boolean removeRootBlock(Block block) {
        mDeletedBlocks.addBlock(block);
        mTrashFragment.getAdapter().notifyDataSetChanged();
        return mRootBlocks.remove(block);
    }

    /**
     * The {@link WorkspaceHelper} for the workspace can be used to get config and style properties
     * and to convert between units.
     *
     * @return The {@link WorkspaceHelper} for this workspace.
     */
    public WorkspaceHelper getWorkspaceHelper() {
        return mWorkspaceHelper;
    }

    /**
     * Set up toolbox's contents.
     *
     * @param toolboxResId The resource id of the set of blocks or block groups to show in the
     * toolbox.
     */
    public void loadToolboxContents(int toolboxResId) {
        InputStream is = mContext.getResources().openRawResource(toolboxResId);
        loadToolboxContents(is);
    }

    /**
     * Set up toolbox's contents.
     *
     * @param source The source of the set of blocks or block groups to show in the toolbox.
     */
    public void loadToolboxContents(InputStream source) {
        mToolboxCategory = BlocklyXmlHelper.loadToolboxFromXml(source, mBlockFactory);
        updateToolbox();
    }

    /**
     * Set up toolbox's contents.
     *
     * @param toolboxXml The xml of the set of blocks or block groups to show in the toolbox.
     */
    public void loadToolboxContents(String toolboxXml) {
        loadToolboxContents(new ByteArrayInputStream(toolboxXml.getBytes()));
    }

    /**
     * Reads the workspace in from a XML stream. This will clear the workspace and replace it with
     * the contents of the xml.
     *
     * @param is The input stream to read from.
     *
     * @throws BlocklyParserException if there was a parse failure.
     */
    public void loadFromXml(InputStream is)
            throws BlocklyParserException {
        resetWorkspace();
        mRootBlocks.addAll(BlocklyXmlHelper.loadFromXml(is, mBlockFactory, mStats));
        for (int i = 0; i < mRootBlocks.size(); i++) {
            mStats.collectStats(mRootBlocks.get(i), true /* recursive */);
        }
    }

    /**
     * Reads the workspace in from a XML stream. This will clear the workspace and replace it with
     * the contents of the xml.
     *
     * @param xml The input stream to read from.
     *
     * @throws BlocklyParserException if there was a parse failure.
     */
    public void loadFromXml(String xml) throws BlocklyParserException {
        loadFromXml(new ByteArrayInputStream(xml.getBytes()));
    }

    /**
     * Gets the {@link BlockFactory} being used by this workspace. This can be used to update or
     * replace the set of known blocks.
     *
     * @return The block factory used by this workspace.
     */
    public BlockFactory getBlockFactory() {
        return mBlockFactory;
    }

    /**
     * Outputs the workspace as an XML string.
     *
     * @param os The output stream to write to.
     *
     * @throws BlocklySerializerException if there was a failure while serializing.
     */
    public void serializeToXml(OutputStream os) throws BlocklySerializerException {
        BlocklyXmlHelper.writeToXml(mRootBlocks, os);
    }

    /**
     * Set up the {@link WorkspaceView} with this workspace's model. This method will perform the
     * following steps:
     * <ul>
     * <li>Set the block touch handler for the view.</li>
     * <li>Configure the dragger for the view.</li>
     * <li>Recursively initialize views for all the blocks in the model and add them to the
     * view.</li>
     * </ul>
     *
     * @param wv The root workspace view to add to.
     */
    public void initWorkspaceView(final WorkspaceView wv) {
        mWorkspaceView = wv;
        mWorkspaceHelper.setWorkspaceView(wv);
        // Tell the workspace helper to pass onTouchBlock events straight through to the WSView.
        mTouchHandler = new WorkspaceHelper.BlockTouchHandler() {
            @Override
            public boolean onTouchBlock(BlockView blockView, MotionEvent motionEvent) {
                return wv.onTouchBlock(blockView, motionEvent);
            }
        };
        mDragger.setWorkspaceView(mWorkspaceView);
        mWorkspaceView.setDragger(mDragger);
        initBlockViews();
    }

    /**
     * Recursively initialize views for all the blocks in the model and add them to the
     * view.
     */
    public void initBlockViews() {
        BlockGroup bg;
        for (int i = 0; i < mRootBlocks.size(); i++) {
            bg = new BlockGroup(mContext, mWorkspaceHelper);
            mWorkspaceHelper.obtainBlockView(mRootBlocks.get(i), bg, mConnectionManager,
                    mTouchHandler);
            mWorkspaceView.addView(bg);
        }
    }

    /**
     * Takes in a block model, creates corresponding views and adds it to the workspace.  Also
     * starts a drag of that block group.
     *
     * @param block The root block to be added to the workspace.
     * @param event The {@link MotionEvent} that caused the block to be added to the workspace.
     * This is used to find the correct position to start the drag event.
     * @param fragment The {@link ToolboxFragment} where the event originated.
     */
    public void addBlockFromToolbox(Block block, MotionEvent event, ToolboxFragment fragment) {
        addBlockWithView(block);
        // let the workspace view know that this is the block we want to drag
        mWorkspaceView.setDragFocus(block.getView(), event);
        // Adjust the event's coordinates from the {@link BlockView}'s coordinate system to
        // {@link WorkspaceView} coordinates.
        mWorkspaceHelper.workspaceToVirtualViewCoordinates(block.getPosition(), mTempViewPoint);
        mWorkspaceView.setDraggingStart((int) event.getX() + mTempViewPoint.x,
                (int) event.getY() + mTempViewPoint.y);
        mWorkspaceView.startDrag();

        // Close the appropriate toolbox
        if (mCanCloseTrash && fragment == mTrashFragment) {
            mFragmentManager.beginTransaction().hide(mTrashFragment).commit();
        }
        // TODO: Remove if we don't see any issues closing the toolbox.
        Log.d(TAG, "Can close toolbox " + mCanCloseToolbox + " toolbox " + mToolboxFragment +
                " fragment " + fragment);
        if (mCanCloseToolbox && fragment == mToolboxFragment) {
            mToolboxDrawer.closeDrawers();
        }
    }

    /**
     * Takes in a block model, creates corresponding views and adds it to the workspace.
     *
     * @param block The {@link Block} to add to the workspace.
     */
    public void addBlockWithView(Block block) {
        BlockGroup bg = new BlockGroup(mContext, mWorkspaceHelper);
        mWorkspaceHelper.obtainBlockView(mContext, block, bg, mConnectionManager, mTouchHandler);
        mWorkspaceView.addView(bg);
        addRootBlock(block);
    }


    /**
     * Reset the workspace view when changing workspaces.  Removes old views and creates all
     * necessary new views.
     */
    public void resetWorkspace() {
        mRootBlocks.clear();
        mStats.clear();
        mDeletedBlocks.clear();
        if (mWorkspaceView != null) {
            mWorkspaceView.removeAllViews();
            initBlockViews();
        }
        // TODO(fenichel): notify adapters when contents change.
    }

    private void updateToolbox() {
        if (mToolboxFragment != null) {
            mToolboxFragment.setContents(mToolboxCategory);
        }
    }

    private void setFragments(final WorkspaceFragment workspace, final TrashFragment trash,
            final ToolboxFragment toolbox, final DrawerLayout toolboxDrawer,
            final FragmentManager manager) {
        mWorkspaceFragment = workspace;
        mTrashFragment = trash;
        mToolboxFragment = toolbox;
        mToolboxDrawer = toolboxDrawer;
        mFragmentManager = manager;

        if (workspace != null) {
            workspace.setWorkspace(this);
            if (trash != null && manager != null) {
                mCanCloseTrash = true; // TODO: also check the config
                if (mCanCloseTrash) {
                    workspace.setTrashClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // Don't open the trash if it's empty.
                            if (!mDeletedBlocks.isEmpty()) {
                                manager.beginTransaction().show(trash).commit();
                            }
                        }
                    });
                    // Start the trash hidden if it can be opened/closed
                    manager.beginTransaction().hide(trash).commit();
                }
            }
        }
        if (trash != null) {
            trash.setWorkspace(this);
            trash.setContents(mDeletedBlocks);
        }
        if (toolbox != null) {
            toolbox.setWorkspace(this);
            mCanCloseToolbox = mToolboxDrawer != null; // TODO: Check config
            updateToolbox();
        }
    }

    @VisibleForTesting
    List<Block> getRootBlocks() {
        return mRootBlocks;
    }

    /**
     * Builder for configuring a new workspace.
     */
    public static class Builder {
        private Context mContext;
        private WorkspaceFragment mWorkspaceFragment;
        private ToolboxFragment mToolboxFragment;
        private DrawerLayout mToolboxDrawer;
        private TrashFragment mTrashFragment;
        private FragmentManager mFragmentManager;
        private AssetManager mAssetManager;
        private int mStyle;

        // TODO: Should these be part of the style?
        private int mToolboxResId;
        private String mToolboxAssetId;
        private String mToolboxXml;
        private int mWorkspaceXmlResId;
        private String mWorkspaceXml;
        private String mWorkspaceAssetId;
        private ArrayList<Integer> mBlockDefResources = new ArrayList<>();
        private ArrayList<String> mBlockDefAssets = new ArrayList<>();
        private ArrayList<Block> mBlockDefs = new ArrayList<>();


        public Builder(Context context) {
            mContext = context;
        }

        public Builder setWorkspaceFragment(WorkspaceFragment workspace) {
            mWorkspaceFragment = workspace;
            return this;
        }

        public Builder setToolboxFragment(ToolboxFragment toolbox, DrawerLayout toolboxDrawer) {
            mToolboxFragment = toolbox;
            mToolboxDrawer = toolboxDrawer;
            return this;
        }

        public Builder setTrashFragment(TrashFragment trash) {
            mTrashFragment = trash;
            return this;
        }

        public Builder setAssetManager(AssetManager manager) {
            mAssetManager = manager;
            return this;
        }

        /**
         * A {@link FragmentManager} is used to show and hide the Toolbox or Trash. It is required
         * if you have set a {@link TrashFragment} or a {@link ToolboxFragment} that is not always
         * visible.
         *
         * @param fragmentManager The support manager to use for showing and hiding fragments.
         *
         * @return this
         */
        public Builder setFragmentManager(FragmentManager fragmentManager) {
            mFragmentManager = fragmentManager;
            return this;
        }

        /**
         * Set the resource id for the style to use when rendering blocks. The style must inherit
         * from {@link com.google.blockly.R.style#BlocklyTheme}.
         *
         * @param styleResId The resource id for the style to use.
         *
         * @return this
         */
        public Builder setBlocklyStyle(int styleResId) {
            mStyle = styleResId;
            return this;
        }

        /**
         * Add a set of block definitions to load from a resource file. These will be added to the
         * set of all known blocks, but will not appear in the user's toolbox unless they are also
         * defined in the toolbox configuration via {@link #setToolboxConfigurationResId(int)}.
         * <p/>
         * The resource must be a json file in the raw directory. If the file contains blocks that
         * were previously defined they will be overridden.
         * <p/>
         * A duplicate block is any block with the same {@link Block#getName() name}.
         *
         * @param blockDefinitionsResId The resource to load blocks from.
         *
         * @return this
         */
        public Builder addBlockDefinitions(int blockDefinitionsResId) {
            mBlockDefResources.add(blockDefinitionsResId);
            return this;
        }

        /**
         * Add a set of block definitions to load from an asset file. These will be added to the
         * set of all known blocks, but will not appear in the user's toolbox unless they are also
         * defined in the toolbox configuration via {@link #setToolboxConfigurationResId(int)}.
         * <p/>
         * The asset name must be a path to a file in the assets directory. If the file contains
         * blocks that were previously defined they will be overridden.
         * <p/>
         * A duplicate block is any block with the same {@link Block#getName() name}.
         *
         * @param assetName the path of the asset to load from.
         *
         * @return this
         */
        public Builder addBlockDefinitionsFromAsset(String assetName) {
            mBlockDefAssets.add(assetName);
            return this;
        }

        /**
         * Adds a list of blocks to the set of all known blocks. These will be added to the
         * set of all known blocks, but will not appear in the user's toolbox unless they are also
         * defined in the toolbox configuration via {@link #setToolboxConfigurationResId(int)}.
         * <p/>
         * These blocks may not have any child blocks attached to them. If these blocks are
         * duplicates of blocks loaded from a resource they will override the block from resources.
         * Blocks added here will always be loaded after any blocks added with
         * {@link #addBlockDefinitions(int)};
         * <p/>
         * A duplicate block is any block with the same {@link Block#getName() name}.
         *
         * @param blocks The list of blocks to add to the workspace.
         *
         * @return this
         */
        public Builder addBlockDefinitions(List<Block> blocks) {
            mBlockDefs.addAll(blocks);
            return this;
        }

        /**
         * Sets the resource to load the toolbox configuration from. This must be an xml resource in
         * the raw directory.
         * <p/>
         * If this is set, {@link #setToolboxConfiguration(String)} and
         * {@link #setToolboxConfigurationAsset(String)} may not be set.
         *
         * @param toolboxResId The resource id for the toolbox config file.
         *
         * @return this
         */
        public Builder setToolboxConfigurationResId(int toolboxResId) {
            if (mToolboxXml != null && mToolboxAssetId != null) {
                throw new IllegalStateException("Toolbox res id may not be set if xml is set.");
            }
            mToolboxResId = toolboxResId;
            return this;
        }

        /**
         * Sets the asset to load the toolbox configuration from. The asset name must be a path to
         * a file in the assets directory.
         * <p/>
         * If this is set, {@link #setToolboxConfiguration(String)} and
         * {@link #setToolboxConfigurationResId(int)} may not be set.
         *
         * @param assetName The asset for the toolbox config file.
         *
         * @return this
         */
        public Builder setToolboxConfigurationAsset(String assetName) {
            if (mToolboxXml != null && mToolboxResId != 0) {
                throw new IllegalStateException("Toolbox res id may not be set if xml is set.");
            }
            mToolboxAssetId = assetName;
            return this;
        }

        /**
         * Sets the XML to use for toolbox configuration.
         * <p/>
         * If this is set, {@link #setToolboxConfigurationResId(int)} and
         * {@link #setToolboxConfigurationAsset(String)} may not be set.
         *
         * @param toolboxXml The XML for configuring the toolbox.
         *
         * @return this
         */
        public Builder setToolboxConfiguration(String toolboxXml) {
            if (mToolboxResId != 0 && mToolboxAssetId != null) {
                throw new IllegalStateException("Toolbox xml may not be set if a res id is set");
            }
            mToolboxXml = toolboxXml;
            return this;
        }

        /**
         * Sets a XML resource to load the initial workspace from. This can be used when
         * creating a new workspace to start with a predefined set of blocks. When changing levels
         * within the same activity {@link Workspace#loadToolboxContents(int)} may be used instead
         * to simply change the Toolbox's configuration.
         * <p/>
         * If this is set, {@link #setStartingWorkspace(String)} and
         * {@link #setStartingWorkspaceAsset(String)} may not be set.
         *
         * @param workspaceXmlResId The resource id for a XML file to load into the workspace.
         * @return this
         */
        public Builder setStartingWorkspace(int workspaceXmlResId) {
            if (mWorkspaceXml != null) {
                throw new IllegalStateException(
                        "Workspace res id may not have been set if xml is set");
            }
            mWorkspaceXmlResId = workspaceXmlResId;
            return this;
        }

        /**
         * Sets an XML String to load the initial workspace from. This can be used when
         * creating a new workspace to start with a predefined set of blocks. When changing levels
         * within the same activity {@link Workspace#loadToolboxContents(int)} may be used instead
         * to simply change the Toolbox's configuration.
         * <p/>
         * If this is set, {@link #setStartingWorkspace(int)} and
         * {@link #setStartingWorkspaceAsset(String)} may not be set.
         *
         * @param workspaceXml The XML to load into the workspace.
         *
         * @return this
         */
        public Builder setStartingWorkspace(String workspaceXml) {
            if (mWorkspaceXmlResId != 0 || mWorkspaceAssetId != null) {
                throw new IllegalStateException(
                        "Workspace xml may not have been set if res id is set");
            }
            mWorkspaceXml = workspaceXml;
            return this;
        }

        /**
         * Sets an asset path to load the initial workspace from. This can be used when
         * creating a new workspace to start with a predefined set of blocks. When changing levels
         * within the same activity {@link Workspace#loadToolboxContents(int)} may be used instead
         * to simply change the Toolbox's configuration without wiping the workspace state.
         * <p/>
         * If this is set, {@link #setStartingWorkspace(int)} and
         * {@link #setStartingWorkspace(String)} may not be set.
         *
         * @param assetPath The asset path to the workspace.
         *
         * @return this
         */
        public Builder setStartingWorkspaceAsset(String assetPath) {
            if (mWorkspaceXmlResId != 0 || mWorkspaceXml != null) {
                throw new IllegalStateException(
                        "Workspace xml may not have been set if res id is set");
            }
            mWorkspaceXml = mWorkspaceAssetId;
            return this;
        }

        /**
         * Create a new workspace using the configuration in this builder.
         *
         * @return A new {@link Workspace}.
         */
        public Workspace build() {
            BlockFactory factory = new BlockFactory(mContext, null);
            for (int i = 0; i < mBlockDefResources.size(); i++) {
                factory.addBlocks(mBlockDefResources.get(i));
            }
            if (mAssetManager != null) {
                for (int i = 0; i < mBlockDefAssets.size(); i++) {
                    try {
                        factory.addBlocks(mAssetManager.open(mBlockDefAssets.get(i)));
                    } catch (IOException e) {
                        throw new IllegalArgumentException("Failed to load block definitions "
                                + mBlockDefAssets.get(i), e);
                    }
                }
            }
            for (int i = 0; i < mBlockDefs.size(); i++) {
                factory.addBlockTemplate(mBlockDefs.get(i));
            }
            Workspace workspace = new Workspace(mContext, factory, mStyle);
            if (mToolboxResId != 0) {
                workspace.loadToolboxContents(mToolboxResId);
            } else if (mToolboxXml != null) {
                workspace.loadToolboxContents(mToolboxXml);
            } else if (mToolboxAssetId != null && mAssetManager != null) {
                try {
                    workspace.loadToolboxContents(mAssetManager.open(mToolboxAssetId));
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to load toolbox from assets "
                            + mToolboxAssetId, e);
                }
            }
            if (mWorkspaceXmlResId != 0) {
                InputStream is = mContext.getResources().openRawResource(mWorkspaceXmlResId);
                workspace.loadFromXml(is);
            } else if (mWorkspaceXml != null) {
                workspace.loadFromXml(mWorkspaceXml);
            }
            workspace.setFragments(mWorkspaceFragment, mTrashFragment, mToolboxFragment,
                    mToolboxDrawer, mFragmentManager);
            return workspace;
        }
    }
}
