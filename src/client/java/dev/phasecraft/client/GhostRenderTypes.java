package dev.phasecraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.phasecraft.PhaseCraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * How ghosts are drawn. Two passes:
 * <ul>
 *   <li>{@link #inFront()}: vanilla's translucent moving-block type, hidden by
 *       visible blocks in front of it like any block;</li>
 *   <li>{@link #seeThrough()}: the same shader with no depth test, drawn
 *       fainter, so hidden machinery inside walls and floors still shows.</li>
 * </ul>
 */
final class GhostRenderTypes {
    /** Ghosts this faint are still drawn (vanilla's translucent cutout, 0.1, would drop the see-through pass). */
    private static final float ALPHA_CUTOUT = 0.02F;

    private static final RenderPipeline SEE_THROUGH_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "pipeline/ghost_see_through"))
                    .withShaderDefine("ALPHA_CUTOUT", ALPHA_CUTOUT)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build());

    /** For "improved transparency" (order-independent transparency), the same without depth test. */
    private static final OitPipelineSet SEE_THROUGH_OIT = RenderPipelines.register(
            OitPipelineSet.builder("phasecraft_ghost_see_through",
                            RenderPipeline.builder(RenderPipelines.GENERIC_BLOCKS_SNIPPET)
                                    .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                                    .withVertexShader("core/block")
                                    .withFragmentShader("core/block")
                                    .withShaderDefine("ALPHA_CUTOUT", ALPHA_CUTOUT))
                    .withAccumulateModifier(accumulate -> accumulate.withBindGroupLayout(BindGroupLayouts.SAMPLER2))
                    .withoutDepthTest()
                    .build());

    private static final RenderType SEE_THROUGH = RenderType.create("phasecraft_ghost_see_through",
            RenderSetup.builder(SEE_THROUGH_PIPELINE)
                    .useLightmap()
                    .withTexture("Sampler0", TextureAtlas.LOCATION_BLOCKS, () -> RenderSystem.getSamplerCache()
                            .getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.NEAREST, true))
                    .sortOnUpload()
                    .setOitPipelines(SEE_THROUGH_OIT)
                    .createRenderSetup());

    private GhostRenderTypes() {}

    static RenderType inFront() {
        return RenderTypes.translucentMovingBlock();
    }

    static RenderType seeThrough() {
        return SEE_THROUGH;
    }
}
