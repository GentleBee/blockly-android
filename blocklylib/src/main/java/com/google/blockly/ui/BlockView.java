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

package com.google.blockly.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.support.annotation.VisibleForTesting;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.google.blockly.R;
import com.google.blockly.control.ConnectionManager;
import com.google.blockly.model.Block;
import com.google.blockly.model.Connection;
import com.google.blockly.model.Input;
import com.google.blockly.model.WorkspacePoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a block and handles laying out all its inputs/fields.
 */
public class BlockView extends FrameLayout {
    private static final String TAG = "BlockView";

    // TODO: Replace these with dimens so they get scaled correctly
    // Minimum height of a block should be the same as an empty field.
    private static final int MIN_HEIGHT = InputView.MIN_HEIGHT;
    // Minimum width of a block should be the same as an empty field.
    private static final int MIN_WIDTH = InputView.MIN_WIDTH;

    // Color of block outline.
    private static final int OUTLINE_COLOR = Color.BLACK;
    private static final int HIGHLIGHT_COLOR = Color.YELLOW;

    private final WorkspaceHelper mHelper;
    private final WorkspaceHelper.BlockTouchHandler mTouchHandler;
    private final Block mBlock;
    private final ConnectionManager mConnectionManager;

    // Objects for drawing the block.
    private final Path mDrawPath = new Path();
    private final Paint mAreaPaint = new Paint();
    private final Paint mBorderPaint = new Paint();
    private final Paint mHighlightPaint = new Paint();
    private final Path mHighlightPath = new Path();

    // Child views for the block inputs and their children.
    private final ArrayList<InputView> mInputViews = new ArrayList<>();

    // Reference points for connectors relative to this view (needed for selective highlighting).
    private final ViewPoint mOutputConnectorOffset = new ViewPoint();
    private final ViewPoint mPreviousConnectorOffset = new ViewPoint();
    private final ViewPoint mNextConnectorOffset = new ViewPoint();
    private final ArrayList<ViewPoint> mInputConnectorOffsets = new ArrayList<>();

    // Current measured size of this block view.
    private final ViewPoint mBlockViewSize = new ViewPoint();
    // Position of the connection currently being updated, for temporary use during updateDrawPath.
    private final ViewPoint mTempConnectionPosition = new ViewPoint();
    // Layout coordinates for inputs in this Block, so they don't have to be computed repeatedly.
    private final ArrayList<ViewPoint> mInputLayoutOrigins = new ArrayList<>();
    // List of widths of multi-field rows when rendering inline inputs.
    private final ArrayList<Integer> mInlineRowWidth = new ArrayList<>();
    private final WorkspacePoint mTempWorkspacePoint = new WorkspacePoint();
    // Fields for highlighting.
    private boolean mHighlightBlock;
    private Connection mHighlightConnection;
    // Offset of the block origin inside the view's measured area.
    private int mLayoutMarginLeft;
    private int mMaxInputFieldsWidth;
    private int mMaxStatementFieldsWidth;
    // Vertical offset for positioning the "Next" block (if one exists).
    private int mNextBlockVerticalOffset;
    // Width of the core "block", ie, rectangle box without connectors or inputs.
    private int mBlockWidth;

    /**
     * Create a new BlockView for the given block using the workspace's style. This constructor is
     * for non-interactive display blocks. If this block is part of a {@link
     * com.google.blockly.model.Workspace}, then {@link BlockView(Context, int, Block,
     * WorkspaceHelper, BlockGroup, View.OnTouchListener)} should be used instead.
     *
     * @param context The context for creating this view.
     * @param block The {@link Block} represented by this view.
     * @param helper The helper for loading workspace configs and doing calculations.
     * @param parentGroup The {@link BlockGroup} this view will live in.
     * @param connectionManager The {@link ConnectionManager} to update when moving connections.
     * @param touchHandler The {@link WorkspaceHelper.BlockTouchHandler} to call when the block is
     * touched.
     */
    public BlockView(Context context, Block block, WorkspaceHelper helper, BlockGroup parentGroup,
            ConnectionManager connectionManager, WorkspaceHelper.BlockTouchHandler touchHandler) {
        this(context, 0 /* default style */, block, helper, parentGroup, connectionManager,
                touchHandler);
    }

    /**
     * Create a new BlockView for the given block using the specified style. The style must extend
     * {@link R.style#DefaultBlockStyle}.
     *
     * @param context The context for creating this view.
     * @param blockStyle The resource id for the style to use on this view.
     * @param block The {@link Block} represented by this view.
     * @param helper The helper for loading workspace configs and doing calculations.
     * @param parentGroup The {@link BlockGroup} this view will live in.
     * @param connectionManager The {@link ConnectionManager} to update when moving connections.
     */
    public BlockView(Context context, int blockStyle, Block block, WorkspaceHelper helper,
            BlockGroup parentGroup, ConnectionManager connectionManager,
            WorkspaceHelper.BlockTouchHandler touchHandler) {
        super(context, null, 0);

        mBlock = block;
        mConnectionManager = connectionManager;
        mHelper = helper;
        mTouchHandler = touchHandler;

        if (parentGroup != null) {
            parentGroup.addView(this);
        }
        block.setView(this);

        setWillNotDraw(false);

        initViews(context, blockStyle, parentGroup);
        initDrawingObjects(context);
    }

    /**
     * Select a connection for highlighted drawing.
     *
     * @param connection The connection whose port to highlight. This must be a connection
     * associated with the {@link Block} represented by this {@link BlockView}
     * instance.
     */
    public void setHighlightConnection(Connection connection) {
        mHighlightBlock = false;
        mHighlightConnection = connection;
        invalidate();
    }

    /**
     * Set highlighting of the entire block, including all inline Value input ports.
     */
    public void setHighlightEntireBlock() {
        mHighlightBlock = true;
        mHighlightConnection = null;
        invalidate();
    }

    /**
     * Clear all highlighting and return everything to normal rendering.
     */
    public void clearHighlight() {
        mHighlightBlock = false;
        mHighlightConnection = null;
        invalidate();
    }

    /**
     * Test whether event hits visible parts of this block and notify {@link WorkspaceView} if it
     * does.
     *
     * @param event The {@link MotionEvent} to handle.
     *
     * @return False if the touch was on the view but not on a visible part of the block; otherwise
     * returns whether the {@link WorkspaceView} says that the event is being handled properly.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return hitTest(event) && mTouchHandler.onTouchBlock(this, event);
    }

    @Override
    public void onDraw(Canvas c) {
        c.drawPath(mDrawPath, mAreaPaint);
        c.drawPath(mDrawPath, mBorderPaint);
        drawHighlights(c);
    }

    /**
     * Measure all children (i.e., block inputs) and compute their sizes and relative positions
     * for use in {@link #onLayout}.
     */
    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (getBlock().getInputsInline()) {
            measureInlineInputs(widthMeasureSpec, heightMeasureSpec);
        } else {
            measureExternalInputs(widthMeasureSpec, heightMeasureSpec);
        }

        mNextBlockVerticalOffset = mBlockViewSize.y;
        if (mBlock.getNextConnection() != null) {
            mBlockViewSize.y += ConnectorHelper.SIZE_PERPENDICULAR;
        }

        if (mBlock.getOutputConnection() != null) {
            mLayoutMarginLeft = ConnectorHelper.SIZE_PERPENDICULAR;
            mBlockViewSize.x += mLayoutMarginLeft;
        } else {
            mLayoutMarginLeft = 0;
        }

        setMeasuredDimension(mBlockViewSize.x, mBlockViewSize.y);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Note that layout must be done regardless of the value of the "changed" parameter.
        boolean rtl = mHelper.useRtL();
        int rtlSign = rtl ? -1 : +1;

        int xFrom = rtl ? mBlockViewSize.x - mLayoutMarginLeft : mLayoutMarginLeft;
        for (int i = 0; i < mInputViews.size(); i++) {
            int rowTop = mInputLayoutOrigins.get(i).y;

            InputView inputView = mInputViews.get(i);
            int inputViewWidth = inputView.getMeasuredWidth();
            int rowFrom = xFrom + rtlSign * mInputLayoutOrigins.get(i).x;
            if (rtl) {
                rowFrom -= inputViewWidth;
            }

            inputView.layout(rowFrom, rowTop, rowFrom + inputViewWidth,
                    rowTop + inputView.getMeasuredHeight());
        }
        updateDrawPath();
        updateConnectorLocations();
    }

    /**
     * @return The block for this view.
     */
    public Block getBlock() {
        return mBlock;
    }

    /**
     * Update the position of the block in workspace coordinates based on the view's location.
     */
    private void updateBlockPosition() {
        // Only update the block position if it isn't a top level block.
        if (mBlock.getPreviousBlock() != null
                || (mBlock.getOutputConnection() != null
                && mBlock.getOutputConnection().getTargetBlock() != null)) {
            mHelper.getWorkspaceCoordinates(this, mTempWorkspacePoint);
            mBlock.setPosition(mTempWorkspacePoint.x, mTempWorkspacePoint.y);
        }
    }

    /**
     * Test whether a {@link MotionEvent} event is (approximately) hitting a visible part of this
     * view.
     * <p/>
     * This is used to determine whether the event should be handled by this view, e.g., to activate
     * dragging or to open a context menu. Since the actual block interactions are implemented at
     * the {@link WorkspaceView} level, there is no need to store the event data in this class.
     *
     * @param event The {@link MotionEvent} to check.
     *
     * @return True if the coordinate of the motion event is on the visible, non-transparent part of
     * this view; false otherwise.
     */
    private boolean hitTest(MotionEvent event) {
        final int eventX = (int) event.getX();
        final int eventY = (int) event.getY();

        // Do the exact same thing for RTL and LTR, with reversed left and right block bounds. Note
        // that the bounds of each InputView include any connected child blocks, so in RTL mode,
        // the left-hand side of the input fields must be obtained from the right-hand side of the
        // input and the field layout width.
        if (mHelper.useRtL()) {
            // First check whether event is in the general horizontal range of the block outline
            // (minus children) and exit if it is not.
            final int blockEnd = mBlockViewSize.x - mLayoutMarginLeft;
            final int blockBegin = blockEnd - mBlockWidth;
            if (eventX < blockBegin || eventX > blockEnd) {
                return false;
            }

            // In the ballpark - now check whether event is on a field of any of this block's
            // inputs. If it is, then the event belongs to this BlockView, otherwise it does not.
            for (int i = 0; i < mInputViews.size(); ++i) {
                final InputView inputView = mInputViews.get(i);
                if (inputView.isOnFields(
                        eventX - (inputView.getRight() - inputView.getFieldLayoutWidth()),
                        eventY - inputView.getTop())) {
                    return true;
                }
            }
        } else {
            final int blockBegin = mLayoutMarginLeft;
            final int blockEnd = mBlockWidth;
            if (eventX < blockBegin || eventX > blockEnd) {
                return false;
            }

            for (int i = 0; i < mInputViews.size(); ++i) {
                final InputView inputView = mInputViews.get(i);
                if (inputView.isOnFields(
                        eventX - inputView.getLeft(), eventY - inputView.getTop())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Draw highlights of block-level connections, or the entire block, if necessary.
     *
     * @param c The canvas to draw on.
     */
    private void drawHighlights(Canvas c) {
        if (mHighlightBlock) {
            c.drawPath(mDrawPath, mHighlightPaint);
        } else if (mHighlightConnection != null) {
            int rtlSign = mHelper.useRtL() ? -1 : +1;
            if (mHighlightConnection == mBlock.getOutputConnection()) {
                ConnectorHelper.getOutputConnectorPath(rtlSign).offset(
                        mOutputConnectorOffset.x, mOutputConnectorOffset.y, mHighlightPath);
            } else if (mHighlightConnection == mBlock.getPreviousConnection()) {
                ConnectorHelper.getPreviousConnectorPath(rtlSign).offset(
                        mPreviousConnectorOffset.x, mPreviousConnectorOffset.y, mHighlightPath);
            } else if (mHighlightConnection == mBlock.getNextConnection()) {
                ConnectorHelper.getNextConnectorPath(rtlSign).offset(
                        mNextConnectorOffset.x, mNextConnectorOffset.y, mHighlightPath);
            } else {
                // If the connection to highlight is not one of the three block-level connectors,
                // then it must be one of the inputs (either a "Next" connector for a Statement or
                // "Input" connector for a Value input). Figure out which input the connection
                // belongs to.
                final Input input = mHighlightConnection.getInput();
                for (int i = 0; i < mInputViews.size(); ++i) {
                    if (mInputViews.get(i).getInput() == input) {
                        final ViewPoint offset = mInputConnectorOffsets.get(i);
                        if (input.getType() == Input.TYPE_STATEMENT) {
                            ConnectorHelper.getNextConnectorPath(rtlSign)
                                    .offset(offset.x, offset.y, mHighlightPath);
                        } else {
                            ConnectorHelper.getValueInputConnectorPath(rtlSign)
                                    .offset(offset.x, offset.y, mHighlightPath);
                        }
                        break;  // Break out of loop once connection has been found.
                    }
                }
            }

            c.drawPath(mHighlightPath, mHighlightPaint);
        }
    }

    /**
     * Measure view and its children with inline inputs.
     * <p>
     * This function does not return a value but has the following side effects. Upon return:
     * <ol>
     * <li>The {@link InputView#measure(int, int)} method has been called for all inputs in
     * this block,</li>
     * <li>{@link #mBlockViewSize} contains the size of the total size of the block view
     * including all its inputs, and</li>
     * <li>{@link #mInputLayoutOrigins} contains the layout positions of all inputs within
     * the block.</li>
     * </ol>
     * </p>
     */
    private void measureInlineInputs(int widthMeasureSpec, int heightMeasureSpec) {
        int inputViewsSize = mInputViews.size();

        // First pass - measure all fields and inputs; compute maximum width of fields over all
        // Statement inputs.
        mMaxStatementFieldsWidth = 0;
        for (int i = 0; i < inputViewsSize; i++) {
            InputView inputView = mInputViews.get(i);
            inputView.measureFieldsAndInputs(widthMeasureSpec, heightMeasureSpec);
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                mMaxStatementFieldsWidth =
                        Math.max(mMaxStatementFieldsWidth, inputView.getTotalFieldWidth());

            }
        }

        // Second pass - compute layout positions and sizes of all inputs.
        int rowLeft = 0;
        int rowTop = 0;

        int rowHeight = 0;
        int maxRowWidth = 0;

        mInlineRowWidth.clear();
        for (int i = 0; i < inputViewsSize; i++) {
            InputView inputView = mInputViews.get(i);

            // If this is a Statement input, force its field width to be the maximum over all
            // Statements, and begin a new layout row.
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                // If the first input is a Statement, add vertical space for drawing connector top.
                if (i == 0) {
                    rowTop += ConnectorHelper.STATEMENT_INPUT_BOTTOM_HEIGHT;
                }

                // Force all Statement inputs to have the same field width.
                inputView.setFieldLayoutWidth(mMaxStatementFieldsWidth);

                // New row BEFORE each Statement input.
                mInlineRowWidth.add(Math.max(rowLeft,
                        mMaxStatementFieldsWidth + ConnectorHelper.STATEMENT_INPUT_INDENT_WIDTH));

                rowTop += rowHeight;
                rowHeight = 0;
                rowLeft = 0;
            }

            mInputLayoutOrigins.get(i).set(rowLeft, rowTop);

            // Measure input view and update row height and width accordingly.
            inputView.measure(widthMeasureSpec, heightMeasureSpec);
            rowHeight = Math.max(rowHeight, inputView.getMeasuredHeight());

            // Set row height for the current input view as maximum over all views in this row so
            // far. A separate, reverse loop below propagates the maximum to earlier inputs in the
            // same row.
            inputView.setRowHeight(rowHeight);

            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                // The block width is that of the widest row.
                maxRowWidth = Math.max(maxRowWidth, inputView.getMeasuredWidth());

                // New row AFTER each Statement input.
                rowTop += rowHeight;
                rowLeft = 0;
                rowHeight = 0;
            } else {
                // For Dummy and Value inputs, row width accumulates. Update maximum width
                // accordingly.
                rowLeft += inputView.getMeasuredWidth();
                maxRowWidth = Math.max(maxRowWidth, rowLeft);
            }
        }

        // Add height of final row. This is non-zero with inline inputs if the final input in the
        // block is not a Statement input.
        rowTop += rowHeight;

        // Third pass - propagate row height maximums backwards. Reset height whenever a Statement
        // input is encoutered.
        int maxRowHeight = 0;
        for (int i = inputViewsSize; i > 0; --i) {
            InputView inputView = mInputViews.get(i - 1);
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                maxRowHeight = 0;
            } else {
                maxRowHeight = Math.max(maxRowHeight, inputView.getRowHeight());
                inputView.setRowHeight(maxRowHeight);
            }
        }

        // If there was at least one Statement input, make sure block is wide enough to fit at least
        // an empty Statement connector. If there were non-empty Statement connectors, they were
        // already taken care of in the loop above.
        if (mMaxStatementFieldsWidth > 0) {
            maxRowWidth = Math.max(maxRowWidth,
                    mMaxStatementFieldsWidth + ConnectorHelper.STATEMENT_INPUT_INDENT_WIDTH);
        }

        // Push width of last input row.
        mInlineRowWidth.add(Math.max(rowLeft,
                mMaxStatementFieldsWidth + ConnectorHelper.STATEMENT_INPUT_INDENT_WIDTH));

        // Block width is the computed width of the widest input row, and at least MIN_WIDTH.
        mBlockViewSize.x = Math.max(MIN_WIDTH, maxRowWidth);
        mBlockWidth = mBlockViewSize.x;

        // Height is vertical position of next (non-existent) inputs row, and at least MIN_HEIGHT.
        mBlockViewSize.y = Math.max(MIN_HEIGHT, rowTop);
    }

    /**
     * Measure view and its children with external inputs.
     * <p>
     * This function does not return a value but has the following side effects. Upon return:
     * <ol>
     * <li>The {@link InputView#measure(int, int)} method has been called for all inputs in
     * this block,</li>
     * <li>{@link #mBlockViewSize} contains the size of the total size of the block view
     * including all its inputs, and</li>
     * <li>{@link #mInputLayoutOrigins} contains the layout positions of all inputs within
     * the block (but note that for external inputs, only the y coordinate of each
     * position is later used for positioning.)</li>
     * </ol>
     * </p>
     */
    private void measureExternalInputs(int widthMeasureSpec, int heightMeasureSpec) {
        mMaxInputFieldsWidth = MIN_WIDTH;
        // Initialize max Statement width as zero so presence of Statement inputs can be determined
        // later; apply minimum size after that.
        mMaxStatementFieldsWidth = 0;

        int maxInputChildWidth = 0;
        int maxStatementChildWidth = 0;

        // First pass - measure fields and children of all inputs.
        boolean hasValueInput = false;
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            inputView.measureFieldsAndInputs(widthMeasureSpec, heightMeasureSpec);

            switch (inputView.getInput().getType()) {
                case Input.TYPE_VALUE: {
                    hasValueInput = true;
                    maxInputChildWidth =
                            Math.max(maxInputChildWidth, inputView.getTotalChildWidth());
                    // fall through
                }
                default:
                case Input.TYPE_DUMMY: {
                    mMaxInputFieldsWidth =
                            Math.max(mMaxInputFieldsWidth, inputView.getTotalFieldWidth());
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    mMaxStatementFieldsWidth =
                            Math.max(mMaxStatementFieldsWidth, inputView.getTotalFieldWidth());
                    maxStatementChildWidth =
                            Math.max(maxStatementChildWidth, inputView.getTotalChildWidth());
                    break;
                }
            }
        }

        // If there was a statement, force all other input fields to be at least as wide as required
        // by the Statement field plus port width.
        if (mMaxStatementFieldsWidth > 0) {
            mMaxStatementFieldsWidth = Math.max(mMaxStatementFieldsWidth, MIN_WIDTH);
            mMaxInputFieldsWidth = Math.max(mMaxInputFieldsWidth,
                    mMaxStatementFieldsWidth + ConnectorHelper.STATEMENT_INPUT_INDENT_WIDTH);
        }

        // Second pass - force all inputs to render fields with the same width and compute positions
        // for all inputs.
        int rowTop = 0;
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                // If the first input is a Statement, add vertical space for drawing connector top.
                if (i == 0) {
                    rowTop += ConnectorHelper.STATEMENT_INPUT_BOTTOM_HEIGHT;
                }

                // Force all Statement inputs to have the same field width.
                inputView.setFieldLayoutWidth(mMaxStatementFieldsWidth);
            } else {
                // Force all Dummy and Value inputs to have the same field width.
                inputView.setFieldLayoutWidth(mMaxInputFieldsWidth);
            }
            inputView.measure(widthMeasureSpec, heightMeasureSpec);

            mInputLayoutOrigins.get(i).set(0, rowTop);

            // The block height is the sum of all the row heights.
            rowTop += inputView.getMeasuredHeight();
            if (inputView.getInput().getType() == Input.TYPE_STATEMENT) {
                rowTop += ConnectorHelper.STATEMENT_INPUT_BOTTOM_HEIGHT;
                // Add bottom connector height to logical row height to make handling touch events
                // easier.
                inputView.setRowHeight(inputView.getMeasuredHeight() +
                        ConnectorHelper.STATEMENT_INPUT_BOTTOM_HEIGHT);
            }
        }

        // Block width is the width of the longest row. Add space for connector if there is at least
        // one Value input.
        mBlockWidth = Math.max(mMaxInputFieldsWidth, mMaxStatementFieldsWidth);
        if (hasValueInput) {
            mBlockWidth += ConnectorHelper.SIZE_PERPENDICULAR;
        }

        // The width of the block view is the width of the block plus the maximum width of any of
        // its children. If there are no children, make sure view is at least as wide as the Block,
        // which accounts for width of unconnected input puts.
        mBlockViewSize.x = Math.max(mBlockWidth,
                Math.max(mMaxInputFieldsWidth + maxInputChildWidth,
                        mMaxStatementFieldsWidth + maxStatementChildWidth));
        mBlockViewSize.y = Math.max(MIN_HEIGHT, rowTop);
    }

    /**
     * A block is responsible for initializing the views all of its fields and sub-blocks,
     * meaning both inputs and next blocks.
     *
     * @param parentGroup The group the current block and all next blocks live in.
     */
    private void initViews(Context context, int blockStyle, BlockGroup parentGroup) {
        List<Input> inputs = mBlock.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            InputView inputView = new InputView(context, blockStyle, in, mHelper);
            mInputViews.add(inputView);
            addView(inputView);
            if (in.getType() != Input.TYPE_DUMMY && in.getConnection().getTargetBlock() != null) {
                // Blocks connected to inputs live in their own BlockGroups.
                BlockGroup bg = new BlockGroup(context, mHelper);
                mHelper.obtainBlockView(context, in.getConnection().getTargetBlock(),
                        bg, mConnectionManager, mTouchHandler);
                inputView.setChildView(bg);
            }
        }

        if (mBlock.getNextBlock() != null) {
            // Next blocks live in the same BlockGroup.
            mHelper.obtainBlockView(mBlock.getNextBlock(), parentGroup, mConnectionManager,
                    mTouchHandler);
        }

        resizeList(mInputConnectorOffsets);
        resizeList(mInputLayoutOrigins);
    }

    private void initDrawingObjects(Context context) {
        mAreaPaint.setColor(mBlock.getColour());
        mAreaPaint.setStyle(Paint.Style.FILL);
        mAreaPaint.setStrokeJoin(Paint.Join.ROUND);

        mBorderPaint.setColor(OUTLINE_COLOR);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(1);
        mBorderPaint.setStrokeJoin(Paint.Join.ROUND);

        mHighlightPaint.setColor(HIGHLIGHT_COLOR);
        mHighlightPaint.setStyle(Paint.Style.STROKE);
        mHighlightPaint.setStrokeWidth(5);
        mHighlightPaint.setStrokeJoin(Paint.Join.ROUND);

        mDrawPath.setFillType(Path.FillType.EVEN_ODD);
    }

    /**
     * Adjust size of an {@link ArrayList} of {@link ViewPoint} objects to match the size of
     * {@link #mInputViews}.
     */
    private void resizeList(ArrayList<ViewPoint> list) {
        if (list.size() != mInputViews.size()) {
            list.ensureCapacity(mInputViews.size());
            if (list.size() < mInputViews.size()) {
                for (int i = list.size(); i < mInputViews.size(); i++) {
                    list.add(new ViewPoint());
                }
            } else {
                while (list.size() > mInputViews.size()) {
                    list.remove(list.size() - 1);
                }
            }
        }
    }

    /**
     * Update path for drawing the block after view size or layout have changed.
     */
    private void updateDrawPath() {
        // TODO(rohlfingt): refactor path drawing code to be more readable. (Will likely be
        // superseded by TODO: implement pretty block rendering.)
        mDrawPath.reset();  // Must reset(), not rewind(), to draw inline input cutouts correctly.

        int xFrom = mLayoutMarginLeft;
        int xTo = mLayoutMarginLeft;

        // For inline inputs, the upper horizontal coordinate of the block boundary varies by
        // section and changes after each Statement input. For external inputs, it is constant as
        // computed in measureExternalInputs.
        int inlineRowIdx = 0;
        if (mBlock.getInputsInline()) {
            xTo += mInlineRowWidth.get(inlineRowIdx);
        } else {
            xTo += mBlockWidth;
        }

        boolean rtl = mHelper.useRtL();
        int rtlSign = rtl ? -1 : +1;

        // In right-to-left mode, mirror horizontal coordinates inside the measured view boundaries.
        if (rtl) {
            xFrom = mBlockViewSize.x - xFrom;
            xTo = mBlockViewSize.x - xTo;
        }

        int yTop = 0;
        int yBottom = mNextBlockVerticalOffset;

        // Top of the block, including "Previous" connector.
        mDrawPath.moveTo(xFrom, yTop);
        if (mBlock.getPreviousConnection() != null) {
            ConnectorHelper.addPreviousConnectorToPath(mDrawPath, xFrom, yTop, rtlSign);
            mPreviousConnectorOffset.set(xFrom, yTop);
        }
        mDrawPath.lineTo(xTo, yTop);

        // Right-hand side of the block, including "Input" connectors.
        for (int i = 0; i < mInputViews.size(); ++i) {
            InputView inputView = mInputViews.get(i);
            ViewPoint inputLayoutOrigin = mInputLayoutOrigins.get(i);
            switch (inputView.getInput().getType()) {
                default:
                case Input.TYPE_DUMMY: {
                    break;
                }
                case Input.TYPE_VALUE: {
                    if (!mBlock.getInputsInline()) {
                        ConnectorHelper.addValueInputConnectorToPath(
                                mDrawPath, xTo, inputLayoutOrigin.y, rtlSign);
                        mInputConnectorOffsets.get(i).set(xTo, inputLayoutOrigin.y);
                    }
                    break;
                }
                case Input.TYPE_STATEMENT: {
                    int xOffset = xFrom + rtlSign * inputView.getFieldLayoutWidth();
                    int connectorHeight = inputView.getTotalChildHeight();

                    // For external inputs, the horizontal end coordinate of the connector bottom is
                    // the same as the one on top. For inline inputs, however, it is the next entry
                    // in the width-by-row table.
                    int xToBottom = xTo;
                    if (mBlock.getInputsInline()) {
                        ++inlineRowIdx;
                        xToBottom = xFrom + rtlSign * mInlineRowWidth.get(inlineRowIdx);
                    }
                    ConnectorHelper.addStatementInputConnectorToPath(mDrawPath,
                            xTo, xToBottom, inputLayoutOrigin.y, xOffset, connectorHeight, rtlSign);
                    mInputConnectorOffsets.get(i).set(xOffset, inputLayoutOrigin.y);
                    // Set new horizontal end coordinate for subsequent inputs.
                    xTo = xToBottom;
                    break;
                }
            }
        }
        mDrawPath.lineTo(xTo, yBottom);

        // Bottom of the block, including "Next" connector.
        if (mBlock.getNextConnection() != null) {
            ConnectorHelper.addNextConnectorToPath(mDrawPath, xFrom, yBottom, rtlSign);
            mNextConnectorOffset.set(xFrom, yBottom);
        }
        mDrawPath.lineTo(xFrom, yBottom);

        // Left-hand side of the block, including "Output" connector.
        if (mBlock.getOutputConnection() != null) {
            ConnectorHelper.addOutputConnectorToPath(mDrawPath, xFrom, yTop, rtlSign);
            mOutputConnectorOffset.set(xFrom, yTop);
        }
        mDrawPath.lineTo(xFrom, yTop);
        // Draw an additional line segment over again to get a final rounded corner.
        mDrawPath.lineTo(xFrom + rtlSign * ConnectorHelper.OFFSET_FROM_CORNER, yTop);

        // Add cutout paths for "holes" from open inline Value inputs.
        if (mBlock.getInputsInline()) {
            for (int i = 0; i < mInputViews.size(); ++i) {
                InputView inputView = mInputViews.get(i);
                if (inputView.getInput().getType() == Input.TYPE_VALUE) {
                    ViewPoint inputLayoutOrigin = mInputLayoutOrigins.get(i);
                    inputView.addInlineCutoutToBlockViewPath(mDrawPath,
                            xFrom + rtlSign * inputLayoutOrigin.x, inputLayoutOrigin.y, rtlSign,
                            mTempConnectionPosition);
                    mInputConnectorOffsets.get(i).set(
                            mTempConnectionPosition.x, mTempConnectionPosition.y);
                }
            }
        }

        mDrawPath.close();
    }

    /**
     * Draw green dots at the model's location of all connections on this block, for debugging.
     *
     * @param c The canvas to draw on.
     */
    private void drawConnectorCenters(Canvas c) {
        List<Connection> connections = mBlock.getAllConnections();
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < connections.size(); i++) {
            Connection conn = connections.get(i);
            if (conn.inDragMode()) {
                if (conn.isConnected()) {
                    paint.setColor(Color.RED);
                } else {
                    paint.setColor(Color.MAGENTA);
                }
            } else {
                if (conn.isConnected()) {
                    paint.setColor(Color.GREEN);
                } else {
                    paint.setColor(Color.CYAN);
                }
            }

            // Compute connector position relative to this view from its offset to block origin in
            // Workspace coordinates.
            mTempWorkspacePoint.set(
                    conn.getPosition().x - mBlock.getPosition().x,
                    conn.getPosition().y - mBlock.getPosition().y);
            mHelper.workspaceToVirtualViewDelta(mTempWorkspacePoint, mTempConnectionPosition);
            c.drawCircle(mTempConnectionPosition.x, mTempConnectionPosition.y, 10, paint);
        }
    }

    /**
     * @return The number of {@link InputView} instances inside this view.
     */
    @VisibleForTesting
    int getInputViewCount() {
        return mInputViews.size();
    }

    /**
     * @return The {@link InputView} for the {@link Input} at the given index.
     */
    @VisibleForTesting
    InputView getInputView(int index) {
        return mInputViews.get(index);
    }

    /**
     * Correctly set the locations of the connections based on their offsets within the
     * {@link BlockView} and the position of the {@link BlockView} itself.  Can be used when the
     * block has moved but not changed shape (e.g. during a drag).
     */
    @VisibleForTesting
    public void updateConnectorLocations() {
        // Ensure we have the right block location before we update the connections.
        updateBlockPosition();

        if (mConnectionManager == null) {
            return;
        }
        final WorkspacePoint blockWorkspacePosition = mBlock.getPosition();
        if (mBlock.getPreviousConnection() != null) {
            mHelper.virtualViewToWorkspaceDelta(mPreviousConnectorOffset, mTempWorkspacePoint);
            mConnectionManager.moveConnectionTo(mBlock.getPreviousConnection(),
                    blockWorkspacePosition, mTempWorkspacePoint);
        }
        if (mBlock.getNextConnection() != null) {
            mHelper.virtualViewToWorkspaceDelta(mNextConnectorOffset, mTempWorkspacePoint);
            mConnectionManager.moveConnectionTo(mBlock.getNextConnection(),
                    blockWorkspacePosition, mTempWorkspacePoint);
        }
        if (mBlock.getOutputConnection() != null) {
            mHelper.virtualViewToWorkspaceDelta(mOutputConnectorOffset, mTempWorkspacePoint);
            mConnectionManager.moveConnectionTo(mBlock.getOutputConnection(),
                    blockWorkspacePosition, mTempWorkspacePoint);
        }
        for (int i = 0; i < mInputViews.size(); i++) {
            InputView inputView = mInputViews.get(i);
            Connection conn = inputView.getInput().getConnection();
            if (conn != null) {
                mHelper.virtualViewToWorkspaceDelta(
                        mInputConnectorOffsets.get(i), mTempWorkspacePoint);
                mConnectionManager.moveConnectionTo(conn,
                        blockWorkspacePosition, mTempWorkspacePoint);
                if (conn.isConnected()) {
                    ((BlockGroup) inputView.getChildView()).updateAllConnectorLocations();
                }
            }
        }
    }

    /**
     * @return Vertical offset for positioning the "Next" block (if one exists). This is relative to
     * the top of this view's area.
     */
    int getNextBlockVerticalOffset() {
        return mNextBlockVerticalOffset;
    }

    /**
     * @return Layout margin on the left-hand side of the block (for optional Output connector).
     */
    int getLayoutMarginLeft() {
        return mLayoutMarginLeft;
    }

}